package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.PermissionLedger;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.types.harness.HarnessData.*;

/** SDK permission adapter; final PREPARED admission is rechecked in startTool's owned transaction. */
public final class OperationAuthorization {
    private final PermissionLedger ledger;
    public OperationAuthorization(PermissionLedger ledger) { this.ledger = ledger; }
    public Decision decide(Lease lease, Call call) { return ledger.operationDecision(lease, call); }
}
