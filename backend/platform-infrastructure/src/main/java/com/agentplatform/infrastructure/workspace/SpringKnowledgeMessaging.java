package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.domain.knowledge.KnowledgeData.BuildCommand;
import com.agentplatform.domain.knowledge.KnowledgeData.Publication;
import com.agentplatform.infrastructure.dao.knowledge.KnowledgeMapper;
import com.agentplatform.types.Uuid7;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
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

/** Dedicated persistent index queue, correlated confirms and manual ACK after claim transaction. */
public final class SpringKnowledgeMessaging implements AutoCloseable {
    public static final String EXCHANGE="ap.commands.v1",QUEUE="ap.knowledge-index.v1",ROUTE="knowledge.index";
    public interface Handoff { BuildCommand claim(Publication publication); void execute(BuildCommand command); }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SpringKnowledgeMessaging.class);
    private final RabbitTemplate publisher;private final SimpleMessageListenerContainer listener;private final KnowledgeMapper mapper;
    public SpringKnowledgeMessaging(CachingConnectionFactory connections,KnowledgeMapper mapper){
        if(!connections.isPublisherConfirms()||!connections.isPublisherReturns())throw new IllegalStateException("Correlated confirms required");
        this.mapper=mapper;publisher=new RabbitTemplate(connections);publisher.setMandatory(true);
        listener=new SimpleMessageListenerContainer(connections);listener.setQueueNames(QUEUE);listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        listener.setConcurrentConsumers(1);listener.setMaxConcurrentConsumers(1);listener.setPrefetchCount(1);listener.setMissingQueuesFatal(false);
        listener.setRecoveryInterval(2000);listener.setAutoStartup(false);
    }
    public void publish(Publication item){
        MessageProperties properties=new MessageProperties();properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);properties.setMessageId(item.id().toString());
        CorrelationData correlation=new CorrelationData(item.id().toString());
        try{publisher.send(EXCHANGE,ROUTE,new Message(JSON.writeValueAsBytes(item),properties),correlation);
            if(!correlation.getFuture().get(2500,TimeUnit.MILLISECONDS).isAck()||correlation.getReturned()!=null)throw new IllegalStateException("Unconfirmed");
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new FileFailure(503,"KNOWLEDGE_DISPATCH_UNCONFIRMED");}
        catch(Exception failure){throw new FileFailure(503,"KNOWLEDGE_DISPATCH_UNCONFIRMED");}
    }
    public void start(Handoff handoff){listener.setMessageListener((ChannelAwareMessageListener)(message,channel)->{
        byte[] body=message.getBody();long tag=message.getMessageProperties().getDeliveryTag();Publication item;
        try{if(body.length>4096)throw new IllegalArgumentException();item=JSON.readValue(body,Publication.class);
            if(item==null||item.id()==null||item.buildId()==null||item.spaceId()==null||item.generation()<1||item.relayOwner()==null)throw new IllegalArgumentException();}
        catch(RuntimeException invalid){mapper.quarantine(Uuid7.next(),sha(body));channel.basicAck(tag,false);return;}
        try{BuildCommand command=handoff.claim(item);channel.basicAck(tag,false);if(command!=null)handoff.execute(command);}
        catch(FileFailure unavailable){if("KNOWLEDGE_DELIVERY_UNKNOWN".equals(unavailable.getMessage())){mapper.quarantine(Uuid7.next(),sha(body));channel.basicAck(tag,false);}else {LOG.warn("Knowledge delivery retained for retry: {}",stableCode(unavailable.getMessage()));channel.abort();}}
        catch(Exception unavailable){LOG.warn("Knowledge delivery retained for retry: {}",failureClassAndLocation(unavailable));channel.abort();}
    });listener.start();}
    private static String stableCode(String value){return value!=null&&value.matches("[A-Z0-9_]{1,64}")?value:"KNOWLEDGE_DELIVERY_FAILED";}
    private static String failureClassAndLocation(Throwable failure){
        Throwable root=org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure);
        StackTraceElement[] frames=root.getStackTrace();
        return root.getClass().getSimpleName()+(frames.length==0?"":" at "+frames[0]);
    }
    private static String sha(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    @Override public void close(){listener.stop();}
}
