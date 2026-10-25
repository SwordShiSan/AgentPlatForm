package com.agentplatform.app.files;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import com.agentplatform.infrastructure.workspace.SpringFileMessaging;
import com.agentplatform.domain.workspace.WorkspaceEffectBoundary;
import com.agentplatform.types.Uuid7;
import java.time.Duration;
import java.util.UUID;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods=false) @Profile({"workspace-api","workspace-worker"})
@ConditionalOnProperty(name={"platform.workspace.enabled","platform.workspace.files.enabled"},havingValue="true")
@org.springframework.boot.context.properties.EnableConfigurationProperties(FileMaintenanceProperties.class)
public class FileMessagingConfiguration {
    @Bean ThreadPoolTaskScheduler fileTimers() {
        var timers=new ThreadPoolTaskScheduler();timers.setPoolSize(4);timers.setThreadNamePrefix("file-control-");
        timers.setRemoveOnCancelPolicy(true);timers.setWaitForTasksToCompleteOnShutdown(false);timers.setAwaitTerminationSeconds(5);return timers;
    }
    @Bean Declarables fileTopology() {
        var exchange=new DirectExchange(SpringFileMessaging.EXCHANGE,true,false);
        var queue=QueueBuilder.durable(SpringFileMessaging.QUEUE).build();
        return new Declarables(exchange,queue,BindingBuilder.bind(queue).to(exchange).with(SpringFileMessaging.ROUTE));
    }
    @Bean(destroyMethod="close") SpringFileMessaging fileMessaging(CachingConnectionFactory connections,FileRepository repository) {
        return new SpringFileMessaging(connections,repository);
    }
    @Bean(destroyMethod="close") @Profile("workspace-worker") FileExecution fileExecution(FileRepository repository,FileRunnerPort runner,AssetStore assets,
            @Qualifier("fileTimers")ThreadPoolTaskScheduler timers) {
        return new FileExecution(repository,runner,assets,timers.getScheduledExecutor());
    }
    @Bean @Profile("workspace-worker") FileReconciliation fileReconciliation(FileRepository repository,FileRunnerPort runner,AssetStore assets) {
        return new FileReconciliation(repository,runner,assets);
    }
    @Bean @Profile("workspace-worker") FileStagingCleanup fileStagingCleanup(FileRepository repository,AssetStore assets){return new FileStagingCleanup(repository,assets);}
    @Bean @Profile("workspace-worker") UploadReconciliation uploadReconciliation(FileRepository repository,AssetStore assets){return new UploadReconciliation(repository,assets);}
    @Bean SmartLifecycle fileLifecycle(FileRepository repository,SpringFileMessaging messaging,@Qualifier("fileTimers")ThreadPoolTaskScheduler timers,
            ObjectProvider<FileExecution> execution,ObjectProvider<FileReconciliation> reconciliation,ObjectProvider<FileStagingCleanup> cleanup,
            Environment selected,WorkspaceEffectBoundary barrier,AssetStore assets,ObjectProvider<UploadReconciliation> uploads,
            FileMaintenanceProperties maintenance) {
        boolean admitScans=maintenance.scanAdmission(selected.acceptsProfiles(Profiles.of("workspace-maintenance")));
        return new SmartLifecycle() {
            private volatile boolean running;
            private java.util.concurrent.ScheduledFuture<?> relay;
            private java.util.concurrent.ScheduledFuture<?> reconciliationPoll;
            private java.util.concurrent.ScheduledFuture<?> cleanupPoll;
            private java.util.concurrent.ScheduledFuture<?> uploadPoll;
            private java.util.concurrent.ScheduledFuture<?> releasePoll;
            private final UUID owner=Uuid7.next();
            @Override public void start() {
                running=true;
                releasePoll=timers.scheduleWithFixedDelay(()->{
                    try{assets.flushStoppedTransfers();}catch(RuntimeException pending){ /* Same-instance confirmed release only; no expiry takeover. */ }
                },Duration.ofSeconds(2));
                relay=timers.scheduleWithFixedDelay(()->{
                    try {
                        repository.blockExpired();
                        if(admitScans)repository.scheduleScan();
                        for(var item:repository.publications(owner)) {
                            boolean confirmed=false;
                            try{messaging.publish(item);confirmed=true;}catch(RuntimeException unconfirmed){ /* Retain outbox. */ }
                            repository.published(item,confirmed);
                        }
                    }catch(RuntimeException unavailable){ /* No direct DB execution or unproven resource release. */ }
                },Duration.ofMillis(500));
                if(selected.acceptsProfiles(Profiles.of("workspace-worker"))) {
                    uploadPoll=timers.scheduleWithFixedDelay(()->{
                        try{uploads.getObject().runOne();}catch(RuntimeException pending){ /* Keep metadata/reservation; no automatic content write. */ }
                    },Duration.ofSeconds(2));
                    cleanupPoll=timers.scheduleWithFixedDelay(()->{
                        try{cleanup.getObject().runOne();}catch(RuntimeException pending){ /* Keep tombstone/reservation until absence is confirmed. */ }
                    },Duration.ofSeconds(10));
                    reconciliationPoll=timers.scheduleWithFixedDelay(()->{
                        try{for(var command:repository.reconciliations())try{reconciliation.getObject().reconcile(command);}catch(RuntimeException pending){ /* Retain unknown facts and reservations. */ }}
                        catch(RuntimeException unavailable){ /* No replay or expired-lease takeover. */ }
                    },Duration.ofSeconds(2));
                    messaging.start(new SpringFileMessaging.Handoff() {
                    @Override public Command claim(Publication item) {
                        if(!running)throw new IllegalStateException("File consumer is stopping");
                        var command=repository.claim(item,owner);if(command!=null)barrier.await("file-claim-after-commit-before-ack");return command;
                    }
                    @Override public void execute(Command command){barrier.await("file-ack-before-runner");execution.getObject().run(command);}
                    });
                }
            }
            @Override public void stop(){running=false;if(relay!=null)relay.cancel(false);if(reconciliationPoll!=null)reconciliationPoll.cancel(false);if(cleanupPoll!=null)cleanupPoll.cancel(false);if(uploadPoll!=null)uploadPoll.cancel(false);if(releasePoll!=null)releasePoll.cancel(false);execution.ifAvailable(FileExecution::close);messaging.close();}
            @Override public boolean isRunning(){return running;}
        };
    }
}
