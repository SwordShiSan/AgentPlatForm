package com.agentplatform.domain.chat;

import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.types.harness.HarnessData.Round;
import java.util.List;
import java.util.UUID;

public interface CancellationLedger {
    record CallState(UUID callId, String status, String reservationState, Long inputTokens,
                     Long outputTokens, Long costMicros, long revision) {}
    record Reconciliation(UUID taskId, String stopStage, boolean stopConfirmed, int unknownCalls,
                          List<CallState> calls, String suggestedAction, boolean excludedFromContext, long version) {}
    record KnownUsage(Call call, Round round, String requestId, boolean real) {}
    enum Decision { KEEP_UNKNOWN, CONFIRMED_NOT_EXECUTED, CONFIRMED_USAGE }
    record Command(UUID callId, UUID commandId, long expectedRevision, Decision decision,
                   UUID evidenceReference, String evidenceHash, String evidenceKind, String operator,
                   String providerRequestId, Long inputTokens, Long outputTokens) {}
    Reconciliation reconciliation(Actor actor, UUID task);
    Reconciliation reconciliationForOperator(UUID task);
    void reconcile(Command command);
    void stopped(Lease lease, String status, String reason, KnownUsage known, String receiptHash);
    void stopUnconfirmed(Lease lease);
}
