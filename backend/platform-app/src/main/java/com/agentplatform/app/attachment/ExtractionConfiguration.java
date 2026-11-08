package com.agentplatform.app.attachment;

import com.agentplatform.api.files.*;
import com.agentplatform.app.preview.*;
import com.agentplatform.app.workspace.WorkspaceControlProperties;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.domain.preview.*;
import com.agentplatform.domain.preview.PreviewData.*;
import com.agentplatform.infrastructure.adapter.repository.files.MyBatisPreviewRepository;
import com.agentplatform.infrastructure.dao.files.*;
import com.agentplatform.infrastructure.workspace.*;
import java.time.Duration;
import java.util.UUID;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

/** Reuses the proven asset job/runner lifecycle with independent typed extraction facts. */
@Configuration(proxyBeanMethods=false) @Profile({"workspace-api","workspace-worker","workspace-runner","preview-runner"})
@ConditionalOnProperty(name={"platform.attachments.enabled","platform.preview.enabled"},havingValue="true")
public class ExtractionConfiguration {
    public static final String PATH="/internal/extractions/v1";
    @Bean PreviewRepository extractionRepository(ExtractionMapper mapper,FileMapper files,TransactionTemplate tx,PreviewProperties properties){
        return new TransactionalPreviewRepository(new MyBatisPreviewRepository(mapper,files,properties.imageId(),properties.bundle(),properties.policyHash(),true),tx);
    }
    @Bean @Profile("workspace-api") DocumentExtractionApi documentExtractionApi(@Qualifier("extractionRepository")PreviewRepository repository){return new DocumentExtractionService(repository);}
    @Bean(destroyMethod="close") @Profile({"workspace-runner","preview-runner"}) PreviewRunnerApi extractionRunnerApi(@Qualifier("extractionRepository")PreviewRepository repository,PreviewRuntime runtime,AssetStore assets,RunnerSignatures signatures,PreviewRunnerConfiguration.Properties properties){
        return new PreviewRunnerService(repository,runtime,assets,signatures,properties.stagingRoot(),PATH);
    }
    @Bean(destroyMethod="close") @Profile("workspace-worker") PreviewRunnerPort extractionRunnerPort(WorkspaceControlProperties control,RunnerSignatures signatures){return new SignedPreviewRunnerClient(control.runnerBase(),signatures,PATH);}
    @Configuration(proxyBeanMethods=false) @Profile({"workspace-api","workspace-worker"})
    @ConditionalOnProperty(name={"platform.attachments.enabled","platform.preview.enabled"},havingValue="true")
    static class Delivery {
        @Bean ThreadPoolTaskScheduler extractionTimers(){var timers=new ThreadPoolTaskScheduler();timers.setPoolSize(3);timers.setThreadNamePrefix("extraction-control-");timers.setRemoveOnCancelPolicy(true);timers.setWaitForTasksToCompleteOnShutdown(false);return timers;}
        @Bean Declarables extractionTopology(){var exchange=new DirectExchange(SpringPreviewMessaging.EXCHANGE,true,false);var queue=QueueBuilder.durable("ap.asset-extract.v1").build();return new Declarables(exchange,queue,BindingBuilder.bind(queue).to(exchange).with("asset.extract"));}
        @Bean(destroyMethod="close") SpringPreviewMessaging extractionMessaging(CachingConnectionFactory connections,@Qualifier("extractionRepository")PreviewRepository repository){return new SpringPreviewMessaging(connections,repository,"ap.asset-extract.v1","asset.extract");}
        @Bean(destroyMethod="close") @Profile("workspace-worker") PreviewExecution extractionExecution(@Qualifier("extractionRepository")PreviewRepository repository,@Qualifier("extractionRunnerPort")PreviewRunnerPort runner,AssetStore assets,@Qualifier("extractionTimers")ThreadPoolTaskScheduler timers){return new PreviewExecution(repository,runner,assets,timers.getScheduledExecutor());}
        @Bean SmartLifecycle extractionLifecycle(@Qualifier("extractionRepository")PreviewRepository repository,@Qualifier("extractionMessaging")SpringPreviewMessaging messaging,@Qualifier("extractionTimers")ThreadPoolTaskScheduler timers,@Qualifier("extractionExecution")ObjectProvider<PreviewExecution> execution,Environment environment){
            return new SmartLifecycle(){
                private volatile boolean running;private java.util.concurrent.ScheduledFuture<?> relay,reconciliation;private final UUID owner=UUID.randomUUID();
                @Override public void start(){running=true;
                    relay=timers.scheduleWithFixedDelay(()->{try{repository.blockExpired();for(var item:repository.publications(owner)){boolean confirmed=false;try{messaging.publish(item);confirmed=true;}catch(RuntimeException unavailable){}repository.published(item,confirmed);}}catch(RuntimeException unavailable){}},Duration.ofMillis(500));
                    if(environment.acceptsProfiles(Profiles.of("workspace-worker"))){
                        reconciliation=timers.scheduleWithFixedDelay(()->{try{for(var command:repository.reconciliations())try{execution.getObject().reconcile(command);}catch(RuntimeException unresolved){}}catch(RuntimeException unavailable){}},Duration.ofSeconds(2));
                        messaging.start(new SpringPreviewMessaging.Handoff(){@Override public Command claim(Publication item){if(!running)throw new IllegalStateException("Extraction consumer stopping");return repository.claim(item,owner);}@Override public void execute(Command command){execution.getObject().run(command);}});
                    }
                }
                @Override public void stop(){running=false;if(relay!=null)relay.cancel(false);if(reconciliation!=null)reconciliation.cancel(false);execution.ifAvailable(PreviewExecution::close);messaging.close();}
                @Override public boolean isRunning(){return running;}
            };
        }
    }
}
