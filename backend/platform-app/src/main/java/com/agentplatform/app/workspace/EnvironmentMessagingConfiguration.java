package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.EnvironmentExecutionData.*;
import com.agentplatform.infrastructure.workspace.SpringEnvironmentMessaging;
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
@ConditionalOnProperty(name={"platform.workspace.enabled","platform.workspace.environment.enabled"},havingValue="true")
public class EnvironmentMessagingConfiguration {
    @Bean Declarables environmentTopology() {
        var exchange=new DirectExchange(SpringEnvironmentMessaging.EXCHANGE,true,false);
        var queue=QueueBuilder.durable(SpringEnvironmentMessaging.QUEUE).build();
        return new Declarables(exchange,queue,BindingBuilder.bind(queue).to(exchange).with(SpringEnvironmentMessaging.ROUTE));
    }
    @Bean(destroyMethod="close") SpringEnvironmentMessaging environmentMessaging(CachingConnectionFactory connections,EnvironmentExecutionRepository repository) {
        return new SpringEnvironmentMessaging(connections,repository);
    }
    @Bean(destroyMethod="close") @Profile("workspace-worker") EnvironmentExecution environmentExecution(EnvironmentExecutionRepository repository,EnvironmentRunnerPort runner,
            @Qualifier("workspaceTimers") ThreadPoolTaskScheduler timers) {return new EnvironmentExecution(repository,runner,timers.getScheduledExecutor());}
    @Bean SmartLifecycle environmentLifecycle(EnvironmentExecutionRepository repository,EnvironmentMaintenanceRepository maintenance,SpringEnvironmentMessaging messaging,
            @Qualifier("workspaceTimers") ThreadPoolTaskScheduler timers,ObjectProvider<EnvironmentExecution> execution,Environment selected) {
        return new SmartLifecycle() {
            private volatile boolean running;
            private java.util.concurrent.ScheduledFuture<?> relay;
            private final UUID owner=Uuid7.next();
            @Override public void start() {
                running=true;
                relay=timers.scheduleWithFixedDelay(()->{
                    try {
                        maintenance.blockExpired();maintenance.scheduleStop();
                        for(var item:repository.publications(owner)) {
                            boolean confirmed=false;
                            try {messaging.publish(item);confirmed=true;}catch(RuntimeException unconfirmed) { /* Durable outbox is retained. */ }
                            repository.published(item,confirmed);
                        }
                    }catch(RuntimeException unavailable) { /* No direct DB execution or capacity release. */ }
                },Duration.ofMillis(500));
                if(selected.acceptsProfiles(Profiles.of("workspace-worker")))messaging.start(new SpringEnvironmentMessaging.Handoff() {
                    @Override public Command claim(Publication item) {
                        if(!running)throw new IllegalStateException("Environment consumer is stopping");
                        return repository.claim(item,owner);
                    }
                    @Override public void execute(Command command) {execution.getObject().run(command);}
                });
            }
            @Override public void stop() {running=false;if(relay!=null)relay.cancel(false);execution.ifAvailable(EnvironmentExecution::close);messaging.close();}
            @Override public boolean isRunning() {return running;}
        };
    }
}
