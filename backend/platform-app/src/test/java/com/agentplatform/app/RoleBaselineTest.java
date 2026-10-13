package com.agentplatform.app;

import com.agentplatform.infrastructure.storage.PostgresStateStorage;
import com.agentplatform.domain.harness.HarnessPorts.LoopDriver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.profiles.active=cloud-runner", "platform.role-ready-file=${java.io.tmpdir}/platform-r03-role-ready"})
class RoleBaselineTest {
    @Autowired private ApplicationContext context;
    @Test void runnerStartsWithoutDatabaseOrBusinessExecution() {
        assertTrue(context.getBeansOfType(PostgresStateStorage.class).isEmpty());
        assertTrue(context.getBeansOfType(LoopDriver.class).isEmpty());
        assertTrue(context.containsBean("baselineRoleObservation"));
    }
}
