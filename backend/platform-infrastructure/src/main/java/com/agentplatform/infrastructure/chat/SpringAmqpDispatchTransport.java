package com.agentplatform.infrastructure.chat;

import com.agentplatform.domain.chat.ChatData.Envelope;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.ChatRepository;
import com.agentplatform.domain.chat.DispatchTransport;
import com.rabbitmq.client.Channel;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.databind.json.JsonMapper;

/** Spring owns channels, reconnects and consumers; the platform owns durable handoff. */
public final class SpringAmqpDispatchTransport implements DispatchTransport {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final CachingConnectionFactory connections;
    private final RabbitTemplate publisher;
    private final SimpleMessageListenerContainer listener;
    private final ChatRepository repository;
    private boolean configured;

    public SpringAmqpDispatchTransport(CachingConnectionFactory connections, ChatRepository repository) {
        this.connections = connections;
        this.repository = repository;
        connections.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connections.setPublisherReturns(true);
        publisher = new RabbitTemplate(connections);
        publisher.setMandatory(true);
        listener = new SimpleMessageListenerContainer(connections);
        listener.setQueueNames("ap.execution.v1");
        listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        listener.setConcurrentConsumers(1);
        listener.setMaxConcurrentConsumers(1);
        listener.setPrefetchCount(1);
        listener.setMissingQueuesFatal(false);
        listener.setRecoveryInterval(2000);
        listener.setAutoStartup(false);
    }

    @Override public void publish(Envelope envelope) {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(envelope.eventId().toString());
        var correlation = new CorrelationData(envelope.eventId().toString());
        try {
            publisher.send("ap.commands.v1", "execution.ready", new Message(JSON.writeValueAsBytes(envelope), properties), correlation);
            var confirm = correlation.getFuture().get(2500, TimeUnit.MILLISECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) throw new ChatFailure(503, "DISPATCH_UNROUTABLE");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ChatFailure(503, "DISPATCH_NOT_CONFIRMED");
        } catch (Exception failure) {
            throw new ChatFailure(503, "DISPATCH_NOT_CONFIRMED");
        }
    }

    @Override public synchronized void consume(Consumer<Delivery> handler) {
        if (!configured) {
            listener.setMessageListener((ChannelAwareMessageListener) (message, channel) -> deliver(message, channel, handler));
            configured = true;
        }
        if (!listener.isRunning()) listener.start();
    }

    private void deliver(Message message, Channel channel, Consumer<Delivery> handler) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        Envelope envelope;
        try {
            if (message.getBody().length > 65536) throw new IllegalArgumentException();
            var node = JSON.readTree(message.getBody());
            if (!node.isObject() || node.size() != 9) throw new IllegalArgumentException();
            envelope = JSON.readValue(message.getBody(), Envelope.class);
            if (envelope.eventId() == null || envelope.spaceId() == null || envelope.aggregateId() == null
                    || envelope.payloadRef() == null || envelope.occurredAt() == null || envelope.kind() == null) throw new IllegalArgumentException();
        } catch (Exception invalid) {
            repository.quarantine("INVALID_MESSAGE_SCHEMA");
            channel.basicAck(tag, false);
            return;
        }
        handler.accept(new Delivery() {
            @Override public Envelope envelope() { return envelope; }
            @Override public void ack() {
                try { channel.basicAck(tag, false); }
                catch (Exception lost) { /* Claim already committed. Inbox/owner fences redelivery. */ }
            }
            @Override public void retryAfterDisconnect() {
                throw new ImmediateRequeueAmqpException("Durable claim did not complete");
            }
        });
    }

    @Override public boolean connected() {
        try { return connections.createConnection().isOpen(); }
        catch (Exception unavailable) { return false; }
    }
    @Override public void close() {
        listener.stop();
        connections.destroy();
    }
}
