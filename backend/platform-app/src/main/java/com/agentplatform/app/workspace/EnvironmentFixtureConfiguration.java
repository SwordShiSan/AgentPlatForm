package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.EnvironmentUseRepository;
import com.agentplatform.infrastructure.adapter.repository.workspace.MyBatisEnvironmentUseRepository;
import com.agentplatform.infrastructure.dao.workspace.*;
import com.agentplatform.infrastructure.workspace.*;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false) @Profile("workspace-environment-fixture")
@ConditionalOnProperty(name="platform.workspace.environment.fixture-enabled",havingValue="true")
@EnableConfigurationProperties({WorkspaceControlProperties.class,EnvironmentProperties.class})
public class EnvironmentFixtureConfiguration {
    static void standalone(Environment selected) {
        if(selected.acceptsProfiles(Profiles.of("workspace-api | workspace-worker | workspace-runner | workspace-environment-ops | identity-local | identity-ops | chat-local | chat-worker | chat-ops | production | cloud-api | cloud-worker")))
            throw new IllegalStateException("Data-plane fixture must be an isolated standalone process");
    }
    @Bean EnvironmentUseRepository fixtureUseRepository(EnvironmentUseMapper uses,EnvironmentPreparationMapper preparation,
            EnvironmentExecutionMapper execution,WorkspaceMapper workspaces,TransactionTemplate transactions,Environment selected) {
        standalone(selected);
        return new TransactionalEnvironmentUseRepository(new MyBatisEnvironmentUseRepository(uses,preparation,execution,workspaces),transactions);
    }
    @Bean RunnerSignatures fixtureSignatures(WorkspaceControlProperties properties) {
        return new RunnerSignatures(WorkspaceKeys.read(properties.privateKeyFile(),properties.keyId(),true),
                WorkspaceKeys.read(properties.runnerPublicKeyFile(),properties.runnerKeyId(),false),"platform-control","platform-runner",Clock.systemUTC());
    }
    @Bean(destroyMethod="close") SignedRunnerHttpClient fixtureRunner(WorkspaceControlProperties properties,RunnerSignatures signatures) {
        return new SignedRunnerHttpClient(properties.runnerBase(),signatures);
    }
    @Bean ThreadPoolTaskScheduler fixtureTimers() {
        var timers=new ThreadPoolTaskScheduler();timers.setPoolSize(2);timers.setThreadNamePrefix("workspace-fixture-");
        timers.setRemoveOnCancelPolicy(true);timers.setWaitForTasksToCompleteOnShutdown(false);timers.setAwaitTerminationSeconds(5);return timers;
    }
    @Bean EnvironmentUseApplicationService fixtureApplication(EnvironmentUseRepository repository,SignedRunnerHttpClient runner,ThreadPoolTaskScheduler timers) {
        return new EnvironmentUseApplicationService(repository,runner,timers.getScheduledExecutor());
    }
}
