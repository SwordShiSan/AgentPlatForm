package com.agentplatform.domain.chat;

import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.types.harness.HarnessData;
import java.util.List;

/** Platform approval facts; the SDK remains the only execution loop. */
public interface ApprovalLedger {
    void stageToolApprovals(Lease lease, List<HarnessData.Call> calls);
    List<HarnessData.Approval> toolApprovals(Lease lease);
}
