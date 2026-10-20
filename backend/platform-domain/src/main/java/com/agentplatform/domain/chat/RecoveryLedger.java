package com.agentplatform.domain.chat;

import com.agentplatform.domain.chat.ChatData.Actor;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.types.harness.HarnessData.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Recovery facts and commands; implementation-specific state stays opaque. */
public interface RecoveryLedger {
    record Startup(boolean restoring, UUID checkpointId, long nextTurn, Instant deadline, List<Message> history) {
        public Startup { history = List.copyOf(history); }
    }
    record Recovery(UUID taskId, boolean eligible, String stage, String reasonCode, long revision,
                    long taskVersion, UUID checkpointId, UUID traceId) {}
    record RestoreCommand(UUID commandId, UUID taskId, UUID checkpointId, UUID stopAttemptId,
                          long expectedTaskVersion, long expectedRevision, String operator) {}
    Startup executionStartup(Lease lease);
    UUID commitCheckpoint(Lease lease, Bundle bundle, CheckpointDraft draft);
    Recovery recovery(Actor actor, UUID task);
    Recovery recoveryForOperator(UUID task);
    Recovery restore(RestoreCommand command);
}
