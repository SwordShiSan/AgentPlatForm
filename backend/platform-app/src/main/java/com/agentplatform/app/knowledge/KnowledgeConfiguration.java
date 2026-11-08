package com.agentplatform.app.knowledge;

import com.agentplatform.api.knowledge.KnowledgeApi;
import com.agentplatform.api.knowledge.PersonalMemoryApi;
import com.agentplatform.domain.knowledge.KnowledgeEncoder;
import com.agentplatform.domain.knowledge.KnowledgeRepository;
import com.agentplatform.infrastructure.adapter.repository.knowledge.MyBatisKnowledgeRepository;
import com.agentplatform.infrastructure.adapter.repository.knowledge.MyBatisMemoryRepository;
import com.agentplatform.infrastructure.dao.knowledge.KnowledgeMapper;
import com.agentplatform.infrastructure.dao.knowledge.MemoryMapper;
import com.agentplatform.infrastructure.knowledge.E5OnnxEncoder;
import com.agentplatform.infrastructure.workspace.SpringKnowledgeMessaging;
import java.time.Duration;
import java.util.UUID;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@Profile({"workspace-api","workspace-worker"})
@ConditionalOnProperty(name="platform.knowledge.enabled",havingValue="true")
@EnableConfigurationProperties(KnowledgeModelProperties.class)
public class KnowledgeConfiguration {
    @Bean KnowledgeRepository knowledgeRepository(KnowledgeMapper mapper,TransactionTemplate tx){return new MyBatisKnowledgeRepository(mapper,tx);}
    @Bean com.agentplatform.domain.knowledge.MemoryRepository memoryRepository(MemoryMapper mapper,TransactionTemplate tx){return new MyBatisMemoryRepository(mapper,tx);}
    @Bean @Profile("workspace-api") KnowledgeApi knowledgeApi(com.agentplatform.domain.knowledge.KnowledgeRepository repository,ObjectProvider<KnowledgeEncoder> encoder){
        return new KnowledgeApplicationService(repository,encoder.getIfAvailable());
    }
    @Bean @Profile("workspace-api") PersonalMemoryApi personalMemoryApi(com.agentplatform.domain.knowledge.MemoryRepository repository){return new PersonalMemoryApplicationService(repository);}
    @Bean(destroyMethod="close") @ConditionalOnProperty(name="platform.knowledge.model.enabled",havingValue="true")
    KnowledgeEncoder knowledgeEncoder(KnowledgeModelProperties properties)throws Exception{return new E5OnnxEncoder(properties.modelFile(),properties.tokenizerFile());}
    @Bean @Profile("workspace-worker") @ConditionalOnProperty(name="platform.knowledge.model.enabled",havingValue="true")
    KnowledgeIndexExecution knowledgeIndexExecution(KnowledgeRepository repository,com.agentplatform.domain.files.AssetStore assets,KnowledgeEncoder encoder,
            @Qualifier("knowledgeTimers")ThreadPoolTaskScheduler timers){
        return new KnowledgeIndexExecution(repository,assets,encoder,timers);
    }
    @Bean @Profile("workspace-worker") @ConditionalOnProperty(name="platform.chat.context.evidence.enabled",havingValue="true")
    TaskContextEvidenceService taskContextEvidenceService(com.agentplatform.domain.knowledge.MemoryRepository memories,KnowledgeRepository knowledge,ObjectProvider<KnowledgeEncoder> encoder){
        return new TaskContextEvidenceService(memories,knowledge,encoder.getIfAvailable());
    }
    @Bean ThreadPoolTaskScheduler knowledgeTimers(){var timers=new ThreadPoolTaskScheduler();timers.setPoolSize(2);timers.setThreadNamePrefix("knowledge-index-");
        timers.setRemoveOnCancelPolicy(true);timers.setWaitForTasksToCompleteOnShutdown(false);timers.setAwaitTerminationSeconds(5);return timers;}
    @Bean Declarables knowledgeTopology(){var exchange=new DirectExchange(SpringKnowledgeMessaging.EXCHANGE,true,false);var queue=QueueBuilder.durable(SpringKnowledgeMessaging.QUEUE).build();
        return new Declarables(exchange,queue,BindingBuilder.bind(queue).to(exchange).with(SpringKnowledgeMessaging.ROUTE));}
    @Bean(destroyMethod="close") SpringKnowledgeMessaging knowledgeMessaging(CachingConnectionFactory connections,KnowledgeMapper mapper){return new SpringKnowledgeMessaging(connections,mapper);}
    @Bean @ConditionalOnProperty(name="platform.knowledge.model.enabled",havingValue="true")
    SmartLifecycle knowledgeLifecycle(com.agentplatform.domain.knowledge.KnowledgeRepository repository,SpringKnowledgeMessaging messaging,
            @Qualifier("knowledgeTimers")ThreadPoolTaskScheduler timers,ObjectProvider<KnowledgeIndexExecution> execution,Environment environment){
        return new SmartLifecycle(){private volatile boolean running;private java.util.concurrent.ScheduledFuture<?> relay;private final UUID owner=UUID.randomUUID();
            @Override public void start(){running=true;relay=timers.scheduleWithFixedDelay(()->{try{for(var item:repository.publications(owner)){boolean confirmed=false;
                try{messaging.publish(item);confirmed=true;}catch(RuntimeException unconfirmed){}repository.published(item,confirmed);}}catch(RuntimeException unavailable){}},Duration.ofMillis(500));
                if(environment.acceptsProfiles(Profiles.of("workspace-worker"))&&execution.getIfAvailable()!=null)messaging.start(new SpringKnowledgeMessaging.Handoff(){
                    @Override public com.agentplatform.domain.knowledge.KnowledgeData.BuildCommand claim(com.agentplatform.domain.knowledge.KnowledgeData.Publication item){if(!running)throw new IllegalStateException("Knowledge worker stopping");return repository.claim(item,owner);}
                    @Override public void execute(com.agentplatform.domain.knowledge.KnowledgeData.BuildCommand command){execution.getObject().run(command);}
                });}
            @Override public void stop(){running=false;if(relay!=null)relay.cancel(false);messaging.close();}
            @Override public boolean isRunning(){return running;}
        };
    }
}
