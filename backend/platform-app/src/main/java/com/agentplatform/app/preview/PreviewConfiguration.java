package com.agentplatform.app.preview;

import com.agentplatform.api.files.PreviewApi;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.domain.preview.*;
import com.agentplatform.domain.preview.PreviewData.*;
import com.agentplatform.infrastructure.adapter.repository.files.MyBatisPreviewRepository;
import com.agentplatform.infrastructure.dao.files.*;
import com.agentplatform.infrastructure.workspace.SpringPreviewMessaging;
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
import org.springframework.core.env.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false) @Profile({"workspace-api","workspace-worker","workspace-runner"})
@ConditionalOnProperty(name={"platform.workspace.enabled","platform.workspace.files.enabled","platform.preview.enabled"},havingValue="true")
@EnableConfigurationProperties(PreviewProperties.class)
public class PreviewConfiguration {
    @Bean PreviewRepository previewRepository(PreviewMapper previews,FileMapper files,TransactionTemplate transactions,PreviewProperties properties) {
        return new TransactionalPreviewRepository(new MyBatisPreviewRepository(previews,files,properties.imageId(),properties.bundle(),properties.policyHash()),transactions);
    }
    @Bean @Profile("workspace-api") PreviewApi previewApi(PreviewRepository repository,AssetStore assets){return new PreviewApplicationService(repository,assets);}
    @Configuration(proxyBeanMethods=false) @Profile({"workspace-api","workspace-worker"})
    @ConditionalOnProperty(name={"platform.workspace.enabled","platform.workspace.files.enabled","platform.preview.enabled"},havingValue="true")
    static class Delivery {
        @Bean ThreadPoolTaskScheduler previewTimers(){var timers=new ThreadPoolTaskScheduler();timers.setPoolSize(3);timers.setThreadNamePrefix("preview-control-");timers.setRemoveOnCancelPolicy(true);timers.setWaitForTasksToCompleteOnShutdown(false);return timers;}
        @Bean Declarables previewTopology(){var exchange=new DirectExchange(SpringPreviewMessaging.EXCHANGE,true,false);var queue=QueueBuilder.durable(SpringPreviewMessaging.QUEUE).build();return new Declarables(exchange,queue,BindingBuilder.bind(queue).to(exchange).with(SpringPreviewMessaging.ROUTE));}
        @Bean(destroyMethod="close") SpringPreviewMessaging previewMessaging(CachingConnectionFactory connections,PreviewRepository repository){return new SpringPreviewMessaging(connections,repository);}
        @Bean(destroyMethod="close") @Profile("workspace-worker") PreviewExecution previewExecution(PreviewRepository repository,PreviewRunnerPort runner,AssetStore assets,@Qualifier("previewTimers")ThreadPoolTaskScheduler timers){return new PreviewExecution(repository,runner,assets,timers.getScheduledExecutor());}
        @Bean SmartLifecycle previewLifecycle(PreviewRepository repository,SpringPreviewMessaging messaging,@Qualifier("previewTimers")ThreadPoolTaskScheduler timers,ObjectProvider<PreviewExecution> execution,Environment environment) {
            return new SmartLifecycle(){
                private volatile boolean running;private java.util.concurrent.ScheduledFuture<?> relay,reconciliation;private final UUID owner=UUID.randomUUID();
                @Override public void start(){running=true;
                    relay=timers.scheduleWithFixedDelay(()->{try{repository.blockExpired();for(var item:repository.publications(owner)){boolean confirmed=false;try{messaging.publish(item);confirmed=true;}catch(RuntimeException unavailable){}repository.published(item,confirmed);}}catch(RuntimeException unavailable){}},Duration.ofMillis(500));
                    if(environment.acceptsProfiles(Profiles.of("workspace-worker"))){
                        reconciliation=timers.scheduleWithFixedDelay(()->{try{for(var command:repository.reconciliations())try{execution.getObject().reconcile(command);}catch(RuntimeException unresolved){}}catch(RuntimeException unavailable){}},Duration.ofSeconds(2));
                        messaging.start(new SpringPreviewMessaging.Handoff(){@Override public Command claim(Publication item){if(!running)throw new IllegalStateException("Preview consumer stopping");return repository.claim(item,owner);}@Override public void execute(Command command){execution.getObject().run(command);}});
                    }
                }
                @Override public void stop(){running=false;if(relay!=null)relay.cancel(false);if(reconciliation!=null)reconciliation.cancel(false);execution.ifAvailable(PreviewExecution::close);messaging.close();}
                @Override public boolean isRunning(){return running;}
            };
        }
    }
}
