package com.agentplatform.infrastructure.capability;

import com.agentplatform.domain.capability.McpProbeRepository;
import com.agentplatform.domain.capability.McpProbeRepository.Command;
import com.agentplatform.domain.capability.McpProbeRepository.Publication;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.databind.json.JsonMapper;

/** Confirmed outbox publication and manual-ACK handoff on a dedicated fixed queue. */
public final class SpringMcpMessaging implements AutoCloseable {
    public static final String EXCHANGE = "ap.commands.v1", QUEUE = "ap.mcp-probe.v1", ROUTE = "mcp.probe";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final McpProbeRepository repository;
    private final RabbitTemplate publisher;
    private final SimpleMessageListenerContainer listener;

    public SpringMcpMessaging(CachingConnectionFactory connections, McpProbeRepository repository) {
        if (!connections.isPublisherConfirms() || !connections.isPublisherReturns())
            throw new IllegalStateException("Correlated confirms and returns required");
        this.repository = repository; publisher = new RabbitTemplate(connections); publisher.setMandatory(true);
        listener = new SimpleMessageListenerContainer(connections); listener.setQueueNames(QUEUE);
        listener.setAcknowledgeMode(AcknowledgeMode.MANUAL); listener.setConcurrentConsumers(1);
        listener.setMaxConcurrentConsumers(1); listener.setPrefetchCount(1); listener.setMissingQueuesFatal(false);
        listener.setRecoveryInterval(2000); listener.setAutoStartup(false);
    }
    public void publish(Publication item) {
        var properties = new MessageProperties(); properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT); properties.setMessageId(item.id().toString());
        var correlation = new CorrelationData(item.id().toString());
        try {
            publisher.send(EXCHANGE, ROUTE, new Message(JSON.writeValueAsBytes(item), properties), correlation);
            if (!correlation.getFuture().get(2500, TimeUnit.MILLISECONDS).isAck() || correlation.getReturned() != null)
                throw new IllegalStateException("Unconfirmed");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new FileFailure(503, "MCP_PROBE_DISPATCH_UNCONFIRMED");
        } catch (Exception failure) { throw new FileFailure(503, "MCP_PROBE_DISPATCH_UNCONFIRMED"); }
    }
    public void start(Consumer<Command> execute) {
        listener.setMessageListener((ChannelAwareMessageListener) (message, channel) -> {
            byte[] body = message.getBody(); long tag = message.getMessageProperties().getDeliveryTag(); Publication item;
            try {
                if (body.length > 4096) throw new IllegalArgumentException();
                item = JSON.readValue(body, Publication.class);
                if (item == null || item.id() == null || item.spaceId() == null || item.probeId() == null
                        || item.relayOwner() == null || item.generation() < 1) throw new IllegalArgumentException();
            } catch (RuntimeException invalid) {
                try { repository.quarantine(UUID.randomUUID(), RunnerSignatures.sha256(body)); channel.basicAck(tag, false); }
                catch (Exception unavailable) { channel.abort(); }
                return;
            }
            try {
                Command command = repository.claim(item, UUID.randomUUID());
                channel.basicAck(tag, false);
                if (command != null) execute.accept(command);
            } catch (FileFailure failure) {
                if ("MCP_PROBE_DELIVERY_UNKNOWN".equals(failure.getMessage())) {
                    try { repository.quarantine(UUID.randomUUID(), RunnerSignatures.sha256(body)); channel.basicAck(tag, false); }
                    catch (Exception unavailable) { channel.abort(); }
                } else channel.abort();
            } catch (Exception unavailable) { channel.abort(); }
        });
        listener.start();
    }
    @Override public void close() { listener.stop(); }
}
