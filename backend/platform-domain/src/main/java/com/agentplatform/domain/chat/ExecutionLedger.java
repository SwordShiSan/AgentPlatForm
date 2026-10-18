package com.agentplatform.domain.chat;

import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.types.harness.HarnessData.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Canonical facts and private state share one fenced transactional authority. */
public interface ExecutionLedger {
    UUID beginTurn(Lease lease);
    void commitRound(Lease lease, UUID turnId, Turn turn, CallResult accepted);
    record CallResult(ChatData.Call call, boolean usageKnown, String requestId, boolean real) {}
    void startTool(Lease lease, com.agentplatform.types.harness.HarnessData.Call call);
    void commitTool(Lease lease, com.agentplatform.types.harness.HarnessData.Call call, Observation result);
    StateSlot state(Lease lease, String key);
    long stateCas(Lease lease, String key, long version, byte[] bytes);
    Set<String> stateKeys(Lease lease);
    void stateDelete(Lease lease, String key);
    UUID sealCheckpoint(Lease lease, Bundle bundle);
    java.util.List<Message> checkpointHistory(Lease lease);
    void validateCheckpoint(Lease lease, UUID checkpoint, Bundle bundle);
    void restoreCheckpoint(Lease lease, UUID checkpoint, Bundle bundle);
    record StoppedOwner(UUID ownerId, UUID taskId, UUID attemptId, long fence,
                        String containerId, String imageId, Instant startedAt,
                        Instant stoppedAt, Instant observedAt, String evidenceHash) {}
    void recoverStopped(StoppedOwner proof);
}
