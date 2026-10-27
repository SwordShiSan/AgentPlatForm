package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.types.harness.HarnessData.Approval;
import io.agentscope.core.event.ConfirmResult;
import java.util.List;

/** SDK confirmation only; platform authorization and one-time consumption stay in the owned ledger. */
final class AgentScopeConfirmationAdapter {
    private AgentScopeConfirmationAdapter() {}
    static List<ConfirmResult> exact(List<Approval> approvals, PlatformMessageCodec codec) {
        return approvals.stream()
                .map(approval -> new ConfirmResult(approval.allowed(), codec.toolUse(approval.call()), List.of()))
                .toList();
    }
}
