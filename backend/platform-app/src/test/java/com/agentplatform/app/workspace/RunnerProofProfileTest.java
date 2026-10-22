package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.RunnerStopProofRepository;
import com.agentplatform.infrastructure.dao.workspace.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunnerProofProfileTest {
    private ApplicationContextRunner context(String...profiles) {
        return new ApplicationContextRunner().withUserConfiguration(RunnerProofOperatorConfiguration.class)
                .withBean(RunnerStopProofMapper.class,()->mock(RunnerStopProofMapper.class))
                .withBean(EnvironmentPreparationMapper.class,()->mock(EnvironmentPreparationMapper.class))
                .withBean(TransactionTemplate.class,()->new TransactionTemplate(mock(PlatformTransactionManager.class)))
                .withInitializer(context->context.getEnvironment().setActiveProfiles(profiles));
    }
    @Test void operatorIsOptInAndDoesNotLeakIntoRuntimeProfiles() {
        for(String profile:new String[]{"workspace-api","workspace-worker","workspace-runner","identity-local","production"})
            context(profile).withPropertyValues("platform.workspace.environment.ops-enabled=true").run(selected->{
                assertNull(selected.getStartupFailure());assertTrue(selected.getBeansOfType(RunnerStopProofRepository.class).isEmpty());
            });
        context("workspace-environment-ops").run(selected->{assertNull(selected.getStartupFailure());assertTrue(selected.getBeansOfType(RunnerStopProofRepository.class).isEmpty());});
        context("workspace-environment-ops").withPropertyValues("platform.workspace.environment.ops-enabled=true").run(selected->{
            assertNull(selected.getStartupFailure());assertEquals(1,selected.getBeansOfType(RunnerStopProofRepository.class).size());
        });
    }
    @Test void operatorCannotShareAnApiOrRunnerProcess() {
        for(String profile:new String[]{"workspace-api","workspace-worker","workspace-runner","identity-local","chat-worker","production"})
            context("workspace-environment-ops",profile).withPropertyValues("platform.workspace.environment.ops-enabled=true").run(selected->{assertNotNull(selected.getStartupFailure());});
    }
}
