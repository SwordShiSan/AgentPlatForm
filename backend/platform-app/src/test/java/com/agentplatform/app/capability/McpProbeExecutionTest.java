package com.agentplatform.app.capability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agentplatform.domain.capability.McpProbeRepository;
import com.agentplatform.infrastructure.chat.CredentialCipher;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class McpProbeExecutionTest {
    @Test void timedOutDiscoveryIsUnknownAndLateResultsCannotBecomeReady() throws Exception {
        var repository = mock(McpProbeRepository.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var unknown = new CountDownLatch(1);
        doAnswer(call -> { unknown.countDown(); return null; }).when(repository).unknown(any(), eq("MCP_PROBE_TIMEOUT_UNKNOWN"));
        var timers = Executors.newScheduledThreadPool(2);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var command = command();
            var execution = new McpProbeExecution(repository, (endpoint, auth) -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return List.of();
            }, new CredentialCipher(new byte[32]), timers, Duration.ofMillis(150));
            var task = worker.submit(() -> execution.run(command));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(unknown.await(2, TimeUnit.SECONDS));
            assertFalse(task.isDone(), "Timeout is not proof that the underlying call has stopped");
            release.countDown(); task.get(2, TimeUnit.SECONDS);
            verify(repository, never()).ready(any(), anyList());
            verify(repository, atLeastOnce()).unknown(eq(command), eq("MCP_PROBE_TIMEOUT_UNKNOWN"));
        } finally { release.countDown(); worker.shutdownNow(); timers.shutdownNow(); }
    }

    private static McpProbeRepository.Command command() {
        return new McpProbeRepository.Command(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, UUID.randomUUID(), 1, 1, "https://example.com/mcp", "NONE", null, null, null,
                "a".repeat(64), UUID.randomUUID());
    }
}
