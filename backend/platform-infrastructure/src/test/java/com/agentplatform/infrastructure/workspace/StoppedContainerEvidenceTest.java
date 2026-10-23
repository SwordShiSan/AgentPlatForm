package com.agentplatform.infrastructure.workspace;

import com.github.dockerjava.api.command.InspectContainerResponse;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoppedContainerEvidenceTest {
    private InspectContainerResponse.ContainerState stopped() {
        var state=mock(InspectContainerResponse.ContainerState.class);
        when(state.getStatus()).thenReturn("exited");
        when(state.getRunning()).thenReturn(false);when(state.getPaused()).thenReturn(false);
        when(state.getRestarting()).thenReturn(false);when(state.getPidLong()).thenReturn(0L);
        when(state.getStartedAt()).thenReturn("2026-10-23T12:00:00.123456789Z");
        when(state.getFinishedAt()).thenReturn("2026-10-23T12:00:00.123456790Z");
        return state;
    }
    @Test void exactStoppedProcessPreservesNanosecondChronology() {
        assertTrue(DockerSandboxRuntime.confirmedContainerStopped(stopped()));
    }
    @Test void activeOrIncompleteContainerNeverMakesRetiredExecSafe() {
        var running=stopped();when(running.getRunning()).thenReturn(true);
        var paused=stopped();when(paused.getPaused()).thenReturn(true);
        var restarting=stopped();when(restarting.getRestarting()).thenReturn(true);
        var pid=stopped();when(pid.getPidLong()).thenReturn(42L);
        var created=stopped();when(created.getStatus()).thenReturn("created");
        var missing=stopped();when(missing.getRunning()).thenReturn(null);
        for(var state:new InspectContainerResponse.ContainerState[]{running,paused,restarting,pid,created,missing})
            assertFalse(DockerSandboxRuntime.confirmedContainerStopped(state));
        assertFalse(DockerSandboxRuntime.confirmedContainerStopped(null));
    }
    @Test void malformedMissingOrNonIncreasingTimesCannotProveStopping() {
        for(String finish:new String[]{"0001-01-01T00:00:00Z","2026-10-23T12:00:00.123456789Z",
                "2026-10-23T12:00:00.123456788Z","invalid"}) {
            var state=stopped();when(state.getFinishedAt()).thenReturn(finish);
            assertFalse(DockerSandboxRuntime.confirmedContainerStopped(state));
        }
        var state=stopped();when(state.getStartedAt()).thenReturn(null);
        assertFalse(DockerSandboxRuntime.confirmedContainerStopped(state));
    }
}
