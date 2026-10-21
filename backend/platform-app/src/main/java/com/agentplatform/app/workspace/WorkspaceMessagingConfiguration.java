package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.WorkspaceData.*;
import com.agentplatform.infrastructure.workspace.SpringWorkspaceMessaging;
import com.agentplatform.types.Uuid7;
import java.util.UUID;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods=false)
@Profile({"workspace-api","workspace-worker"})
@ConditionalOnProperty(name="platform.workspace.enabled",havingValue="true")
public class WorkspaceMessagingConfiguration {
    /** Standalone control-plane processes do not need chat credentials or a provider. */
    @Configuration(proxyBeanMethods=false) @Profile("(workspace-api | workspace-worker) & !chat-local & !chat-worker")
    @ConditionalOnProperty(name="platform.workspace.enabled",havingValue="true")
    @org.springframework.boot.context.properties.EnableConfigurationProperties(WorkspaceMessagingProperties.class)
    static class StandaloneConnections {
        @Bean(destroyMethod="destroy") CachingConnectionFactory workspaceConnections(WorkspaceMessagingProperties properties) throws java.io.IOException {
            var connection=new CachingConnectionFactory(properties.host(),properties.port());
            connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);connection.setPublisherReturns(true);
            connection.setVirtualHost("/agentplatform");connection.setUsername(properties.user());
            connection.setPassword(java.nio.file.Files.readString(properties.passwordFile()).strip());
            connection.setConnectionTimeout(3000);connection.setRequestedHeartBeat(15);return connection;
        }
    }
    @Bean(destroyMethod="close") SpringWorkspaceMessaging workspaceMessaging(CachingConnectionFactory connections,WorkspaceRepository repository) {
        return new SpringWorkspaceMessaging(connections,repository);
    }
    @Bean ThreadPoolTaskScheduler workspaceTimers() {
        var timers=new ThreadPoolTaskScheduler();timers.setPoolSize(3);timers.setThreadNamePrefix("workspace-control-");
        timers.setRemoveOnCancelPolicy(true);timers.setWaitForTasksToCompleteOnShutdown(false);timers.setAwaitTerminationSeconds(5);return timers;
    }
    @Bean @Profile("workspace-worker") WorkspaceExecution workspaceExecution(WorkspaceRepository repository,RunnerControlPort runner,@org.springframework.beans.factory.annotation.Qualifier("workspaceTimers") ThreadPoolTaskScheduler workspaceTimers) {
        return new WorkspaceExecution(repository,runner,workspaceTimers.getScheduledExecutor());
    }
    @Bean SmartLifecycle workspaceLifecycle(WorkspaceRepository repository,SpringWorkspaceMessaging messaging,
            @org.springframework.beans.factory.annotation.Qualifier("workspaceTimers") ThreadPoolTaskScheduler workspaceTimers,Environment environment,org.springframework.beans.factory.ObjectProvider<WorkspaceExecution> execution,WorkspaceFixtureBarrier barrier) {
        return new SmartLifecycle() {
            private boolean running;
            private java.util.concurrent.ScheduledFuture<?> relay;
            private final UUID owner=Uuid7.next();
            private final Object lane=new Object();
            @Override public void start() {
                if(environment.acceptsProfiles(Profiles.of("workspace-api"))&&!environment.acceptsProfiles(Profiles.of("identity-local")))
                    throw new IllegalStateException("workspace-api requires existing identity-local boundary");
                running=true;
                relay=workspaceTimers.scheduleWithFixedDelay(()->{
                    try {
                        repository.expireUnresolved(); // Block ambiguity; do not free resources or manufacture a stopped owner.
                        for(var item:repository.publications(owner)) {
                            boolean confirmed=false;
                            try{messaging.publish(item);confirmed=true;}catch(RuntimeException failed){ /* Keep durable outbox. */ }
                            repository.published(item,confirmed);
                        }
                    }catch(RuntimeException unavailable){ /* Next fixed-delay poll; never execute DB jobs directly. */ }
                },java.time.Duration.ofMillis(500));
                if(environment.acceptsProfiles(Profiles.of("workspace-worker"))) messaging.start(new SpringWorkspaceMessaging.Handoff() {
                    @Override public Command claim(Publication item) { synchronized(lane){var command=repository.claim(item,owner);if(command!=null)barrier.await("claim-after-commit-before-ack");return command;} }
                    @Override public void execute(Command command) { synchronized(lane){barrier.await("ack-before-runner");execution.getObject().run(command);} }
                });
            }
            @Override public void stop(){running=false;if(relay!=null)relay.cancel(false);messaging.close();}
            @Override public boolean isRunning(){return running;}
        };
    }
}
