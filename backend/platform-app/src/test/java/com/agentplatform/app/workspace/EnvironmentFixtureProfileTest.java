package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.WorkspaceFailure;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentFixtureProfileTest {
    @Test void fixedFixtureCannotMixWithAnyRuntimeOrPrivilegedOperatorProfile() {
        for(String profile:new String[]{"workspace-api","workspace-worker","workspace-runner","workspace-environment-ops","identity-local","identity-ops",
                "chat-local","chat-worker","chat-ops","production","cloud-api","cloud-worker"}) {
            var selected=new StandardEnvironment();selected.setActiveProfiles("workspace-environment-fixture",profile);
            assertThrows(IllegalStateException.class,()->EnvironmentFixtureConfiguration.standalone(selected));
        }
        var selected=new StandardEnvironment();selected.setActiveProfiles("workspace-environment-fixture");
        assertDoesNotThrow(()->EnvironmentFixtureConfiguration.standalone(selected));
    }
    @Test void fixtureDoesNotLoadInRuntimeOrWithoutItsExplicitFlag() {
        for(String profile:new String[]{"workspace-api","workspace-worker","workspace-runner","workspace-environment-fixture","production"})
            new ApplicationContextRunner().withUserConfiguration(EnvironmentFixtureConfiguration.class,EnvironmentFixtureOperator.class)
                    .withInitializer(context->context.getEnvironment().setActiveProfiles(profile))
                    .withPropertyValues("platform.workspace.environment.fixture-enabled=false").run(context->{
                        assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(EnvironmentFixtureOperator.class).isEmpty());
                        assertTrue(context.getBeansOfType(EnvironmentUseApplicationService.class).isEmpty());
                    });
    }
    @Test void missingScopeFileIsRejectedBeforeAnyOperation() {
        assertEquals("FIXTURE_SCOPE_NOT_PRIVATE",assertThrows(WorkspaceFailure.class,()->EnvironmentFixtureOperator.readScope(Path.of("missing-r32-fixture-scope.json"))).code());
    }
}
