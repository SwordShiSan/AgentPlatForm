package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.domain.preview.*;
import com.agentplatform.domain.preview.PreviewData.*;
import com.agentplatform.types.Uuid7;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.databind.json.JsonMapper;

/** Asset jobs use a managed manual-ACK consumer, never the chat execution queue. */
public final class SpringPreviewMessaging implements AutoCloseable {
    public static final String EXCHANGE="ap.commands.v1",QUEUE="ap.asset.v1",ROUTE="asset.process";
    public interface Handoff {Command claim(Publication publication);void execute(Command command);}
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final PreviewRepository repository;private final RabbitTemplate publisher;private final SimpleMessageListenerContainer listener;
    public SpringPreviewMessaging(CachingConnectionFactory connections,PreviewRepository repository) {
        if(!connections.isPublisherConfirms()||!connections.isPublisherReturns())throw new IllegalStateException("Correlated confirms/returns required");
        this.repository=repository;publisher=new RabbitTemplate(connections);publisher.setMandatory(true);
        listener=new SimpleMessageListenerContainer(connections);listener.setQueueNames(QUEUE);listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        listener.setConcurrentConsumers(1);listener.setMaxConcurrentConsumers(1);listener.setPrefetchCount(1);listener.setMissingQueuesFatal(false);
        listener.setRecoveryInterval(2000);listener.setAutoStartup(false);
    }
    public void publish(Publication item) {
        var properties=new MessageProperties();properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);properties.setMessageId(item.id().toString());
        var correlation=new CorrelationData(item.id().toString());
        try{publisher.send(EXCHANGE,ROUTE,new Message(JSON.writeValueAsBytes(item),properties),correlation);
            if(!correlation.getFuture().get(2500,TimeUnit.MILLISECONDS).isAck()||correlation.getReturned()!=null)throw new IllegalStateException("Unconfirmed");
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new FileFailure(503,"PREVIEW_DISPATCH_UNCONFIRMED");}
        catch(Exception failed){throw new FileFailure(503,"PREVIEW_DISPATCH_UNCONFIRMED");}
    }
    public void start(Handoff handoff) {
        listener.setMessageListener((ChannelAwareMessageListener)(message,channel)->{
            var body=message.getBody();long tag=message.getMessageProperties().getDeliveryTag();Publication item;
            try{if(body.length>4096)throw new IllegalArgumentException();item=JSON.readValue(body,Publication.class);
                if(item==null||item.id()==null||item.previewId()==null||item.spaceId()==null||item.generation()<1||item.relayOwner()==null)throw new IllegalArgumentException();
            }catch(RuntimeException invalid){repository.quarantine(Uuid7.next(),RunnerSignatures.sha256(body));channel.basicAck(tag,false);return;}
            try{var command=handoff.claim(item);channel.basicAck(tag,false);if(command!=null)handoff.execute(command);}
            catch(FileFailure invalid){if("PREVIEW_DELIVERY_UNKNOWN".equals(invalid.getMessage())){repository.quarantine(Uuid7.next(),RunnerSignatures.sha256(body));channel.basicAck(tag,false);}else channel.abort();}
            catch(Exception unavailable){channel.abort();}
        });listener.start();
    }
    @Override public void close(){listener.stop();}
}
