package com.agentplatform.app.workspace;

import com.agentplatform.api.workspace.WorkspaceEnvironmentApi;
import com.agentplatform.domain.workspace.SandboxRuntime;
import com.agentplatform.infrastructure.dao.workspace.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class EnvironmentProfileTest {
    private ApplicationContextRunner context(String...profiles) {
        return new ApplicationContextRunner().withUserConfiguration(EnvironmentConfiguration.class,EnvironmentConfiguration.RuntimeConfiguration.class)
                .withBean(EnvironmentExecutionMapper.class,()->mock(EnvironmentExecutionMapper.class))
                .withBean(EnvironmentPreparationMapper.class,()->mock(EnvironmentPreparationMapper.class))
                .withBean(EnvironmentMaintenanceMapper.class,()->mock(EnvironmentMaintenanceMapper.class))
                .withBean(EnvironmentUseMapper.class,()->mock(EnvironmentUseMapper.class))
                .withBean(WorkspaceMapper.class,()->mock(WorkspaceMapper.class))
                .withBean(TransactionTemplate.class,()->new TransactionTemplate(mock(PlatformTransactionManager.class)))
                .withPropertyValues("platform.workspace.environment.image-id=sha256:"+"a".repeat(64),"platform.workspace.environment.policy-hash="+"b".repeat(64))
                .withInitializer(context->context.getEnvironment().setActiveProfiles(profiles));
    }
    @Test void disabledAndOperatorProfilesDoNotLoadEnvironmentRuntime() {
        for(String profile:new String[]{"identity-ops","workspace-environment-ops","production","workspace-api","workspace-worker","workspace-runner"})
            context(profile).withPropertyValues("platform.workspace.enabled=true","platform.workspace.environment.enabled=false").run(selected->{
                assertNull(selected.getStartupFailure());assertTrue(selected.getBeansOfType(SandboxRuntime.class).isEmpty());assertTrue(selected.getBeansOfType(WorkspaceEnvironmentApi.class).isEmpty());
            });
        context("workspace-api","identity-local").withPropertyValues("platform.workspace.enabled=false","platform.workspace.environment.enabled=true").run(selected->{
            assertNull(selected.getStartupFailure());assertTrue(selected.getBeansOfType(WorkspaceEnvironmentApi.class).isEmpty());
        });
    }
    @Test void apiRequiresIdentityAndNeitherApiNorWorkerReceivesDockerRuntime() {
        context("workspace-api").withPropertyValues("platform.workspace.enabled=true","platform.workspace.environment.enabled=true").run(selected->assertNotNull(selected.getStartupFailure()));
        context("workspace-api","identity-local").withPropertyValues("platform.workspace.enabled=true","platform.workspace.environment.enabled=true").run(selected->{
            assertNull(selected.getStartupFailure());assertEquals(1,selected.getBeansOfType(WorkspaceEnvironmentApi.class).size());assertTrue(selected.getBeansOfType(SandboxRuntime.class).isEmpty());
        });
        context("workspace-worker").withPropertyValues("platform.workspace.enabled=true","platform.workspace.environment.enabled=true").run(selected->{
            assertNull(selected.getStartupFailure());assertTrue(selected.getBeansOfType(WorkspaceEnvironmentApi.class).isEmpty());assertTrue(selected.getBeansOfType(SandboxRuntime.class).isEmpty());
        });
    }
}
