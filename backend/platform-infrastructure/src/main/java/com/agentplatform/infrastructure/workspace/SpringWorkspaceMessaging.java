package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.WorkspaceData.*;
import com.agentplatform.types.Uuid7;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.databind.json.JsonMapper;

public final class SpringWorkspaceMessaging implements AutoCloseable {
    public interface Handoff { Command claim(Publication publication); void execute(Command command); }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final RabbitTemplate publisher;
    private final SimpleMessageListenerContainer listener;
    private final WorkspaceRepository repository;
    public SpringWorkspaceMessaging(CachingConnectionFactory connections,WorkspaceRepository repository) {
        this.repository=repository;
        // Configure confirms/returns at factory creation, before any cached channel exists.
        if(!connections.isPublisherConfirms()||!connections.isPublisherReturns())throw new IllegalStateException("Workspace AMQP requires preconfigured correlated confirms and returns");
        publisher=new RabbitTemplate(connections);publisher.setMandatory(true);
        listener=new SimpleMessageListenerContainer(connections);listener.setQueueNames("ap.workspace.provision.v1");
        listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);listener.setConcurrentConsumers(1);listener.setMaxConcurrentConsumers(1);
        listener.setPrefetchCount(1);listener.setMissingQueuesFatal(false);listener.setRecoveryInterval(2000);listener.setAutoStartup(false);
    }
    public void publish(Publication publication) {
        var props=new MessageProperties();props.setContentType(MessageProperties.CONTENT_TYPE_JSON);props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);props.setMessageId(publication.id().toString());
        var confirm=new CorrelationData(publication.id().toString());
        try {
            publisher.send("ap.workspace.commands.v1","workspace.provision",new Message(JSON.writeValueAsBytes(publication),props),confirm);
            if(!confirm.getFuture().get(2500,TimeUnit.MILLISECONDS).isAck()||confirm.getReturned()!=null)throw new WorkspaceFailure(503,"WORKSPACE_DISPATCH_UNCONFIRMED");
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new WorkspaceFailure(503,"WORKSPACE_DISPATCH_UNCONFIRMED");}
        catch(Exception uncertain){throw new WorkspaceFailure(503,"WORKSPACE_DISPATCH_UNCONFIRMED");}
    }
    public void start(Handoff handoff) {
        listener.setMessageListener((ChannelAwareMessageListener)(message,channel)->{
            byte[] raw=message.getBody();long tag=message.getMessageProperties().getDeliveryTag();Publication envelope;
            try {
                if(raw.length>4096)throw new IllegalArgumentException();envelope=JSON.readValue(raw,Publication.class);
                if(envelope.id()==null||envelope.operationId()==null||envelope.spaceId()==null||envelope.workspaceId()==null||envelope.relayOwner()==null||envelope.generation()<1||envelope.fence()<1)throw new IllegalArgumentException();
            }catch(RuntimeException invalid){repository.quarantine(Uuid7.next(),"INVALID_WORKSPACE_DELIVERY",RunnerSignatures.sha256(raw));channel.basicAck(tag,false);return;}
            try {
                Command command=handoff.claim(envelope); // The transaction has committed before ACK.
                channel.basicAck(tag,false);
                if(command!=null)handoff.execute(command);
            }catch(Exception unavailable){channel.abort(); /* Spring owns reconnect/backoff; no DB execution fallback. */}
        });
        listener.start();
    }
    @Override public void close(){listener.stop();}
}
