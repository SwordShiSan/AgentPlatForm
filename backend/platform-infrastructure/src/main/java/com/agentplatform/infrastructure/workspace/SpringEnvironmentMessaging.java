package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.EnvironmentExecutionData.*;
import com.agentplatform.types.Uuid7;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.databind.json.JsonMapper;

/** A versioned environment queue on the existing managed connection factory. */
public final class SpringEnvironmentMessaging implements AutoCloseable {
    public static final String EXCHANGE="ap.workspace.environments.v2",QUEUE="ap.workspace.environment.v2",ROUTE="workspace.environment";
    public interface Handoff {Command claim(Publication publication);void execute(Command command);}
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final EnvironmentExecutionRepository repository;
    private final RabbitTemplate publisher;
    private final SimpleMessageListenerContainer listener;
    public SpringEnvironmentMessaging(CachingConnectionFactory connections,EnvironmentExecutionRepository repository) {
        if(!connections.isPublisherConfirms()||!connections.isPublisherReturns())throw new IllegalStateException("Correlated confirms/returns must precede channel creation");
        this.repository=repository;publisher=new RabbitTemplate(connections);publisher.setMandatory(true);
        listener=new SimpleMessageListenerContainer(connections);listener.setQueueNames(QUEUE);listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        listener.setConcurrentConsumers(1);listener.setMaxConcurrentConsumers(1);listener.setPrefetchCount(1);
        listener.setMissingQueuesFatal(false);listener.setRecoveryInterval(2000);listener.setAutoStartup(false);
    }
    public void publish(Publication publication) {
        var properties=new MessageProperties();properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);properties.setMessageId(publication.id().toString());
        var correlation=new CorrelationData(publication.id().toString());
        try {
            publisher.send(EXCHANGE,ROUTE,new Message(JSON.writeValueAsBytes(publication),properties),correlation);
            if(!correlation.getFuture().get(2500,TimeUnit.MILLISECONDS).isAck()||correlation.getReturned()!=null)throw new WorkspaceFailure(503,"ENVIRONMENT_DISPATCH_UNCONFIRMED");
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new WorkspaceFailure(503,"ENVIRONMENT_DISPATCH_UNCONFIRMED");}
        catch(Exception unavailable) {throw new WorkspaceFailure(503,"ENVIRONMENT_DISPATCH_UNCONFIRMED");}
    }
    public void start(Handoff handoff) {
        listener.setMessageListener((ChannelAwareMessageListener)(message,channel)->{
            byte[] raw=message.getBody();long tag=message.getMessageProperties().getDeliveryTag();Publication publication;
            try {
                if(raw.length>4096)throw new IllegalArgumentException();publication=JSON.readValue(raw,Publication.class);
                if(publication==null||publication.id()==null||publication.operationId()==null||publication.spaceId()==null||publication.workspaceId()==null
                        ||publication.environmentId()==null||publication.relayOwner()==null||publication.generation()<1||publication.fence()<1)throw new IllegalArgumentException();
            } catch(RuntimeException invalid) {
                repository.quarantine(Uuid7.next(),"INVALID_ENVIRONMENT_DELIVERY",RunnerSignatures.sha256(raw));channel.basicAck(tag,false);return;
            }
            try {
                var command=handoff.claim(publication); // The owner/inbox transaction has committed.
                channel.basicAck(tag,false);
                if(command!=null)handoff.execute(command);
            } catch(Exception unavailable) {channel.abort(); /* Framework reconnect; no alternate DB execution path. */}
        });
        listener.start();
    }
    @Override public void close() {listener.stop();}
}
