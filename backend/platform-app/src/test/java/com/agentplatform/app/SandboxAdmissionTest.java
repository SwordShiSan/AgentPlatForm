package com.agentplatform.app;

import com.agentplatform.domain.health.SandboxAdmission;
import org.junit.jupiter.api.Test;
import static com.agentplatform.domain.health.SandboxAdmission.*;
import static org.junit.jupiter.api.Assertions.*;

class SandboxAdmissionTest {
    @Test void missingSecurityCapabilityDisablesRatherThanQueuesOrFallsBack() {
        assertEquals(Result.DISABLED, decide(false, true, 0, 4096*MIB, 10240*MIB));
        assertEquals(Result.DISABLED, decide(true, false, 0, 4096*MIB, 10240*MIB));
    }
    @Test void memoryDiskAndSingleSlotBoundariesDoNotOvercommit() {
        assertEquals(Result.QUEUED, decide(true, true, 1, 4096*MIB, 10240*MIB));
        assertEquals(Result.QUEUED, decide(true, true, 0, 1152*MIB-1, 10240*MIB));
        assertEquals(Result.QUEUED, decide(true, true, 0, 4096*MIB, 5120*MIB-1));
        assertEquals(Result.ALLOW, decide(true, true, 0, 1152*MIB, 5120*MIB));
        assertThrows(IllegalArgumentException.class, () -> decide(true,true,-1,0,0));
    }
}
