package com.agentplatform.domain.chat;

import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.types.PlanInteractionData.*;
import com.agentplatform.types.harness.HarnessData;
import java.util.List;
import java.util.UUID;

public interface PlanLedger {
    Plan plan(Actor actor,UUID task);
    Plan editPlan(Actor actor,UUID task,long expected,Edit edit);
    List<Pending> pending(Actor actor,UUID conversation);
    Accepted answer(Actor actor,UUID task,UUID interaction,UUID key,Answer answer);
    void stageControls(Lease lease,List<HarnessData.Call> calls);
    HarnessData.Decision controlDecision(Lease lease,HarnessData.Call call);
    HarnessData.Observation executeControl(Lease lease,HarnessData.Call call);
    void waitingStopped(Lease lease,String receiptHash);
    List<HarnessData.Approval> controlApprovals(Lease lease);
    boolean planMode(Lease lease);
    void consumeOptional(Lease lease);
}
