package com.agentplatform.domain.chat;

import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.domain.harness.HarnessPorts.ToolExchange;
import com.agentplatform.types.harness.HarnessData.Call;
import com.agentplatform.types.harness.HarnessData.ToolDefinition;
import java.util.List;

/** Application-owned production composition, distinct from the SDK's local filesystem tools. */
public interface WorkspaceToolRuntime {
    List<ToolDefinition> definitions(Lease lease);
    void prepare(Lease lease,List<Call> calls);
    /** Short transaction only: admission, once consumption and child job bindings. */
    void admit(Lease lease,Call call);
    /** External work starts after admission commits. */
    ToolExchange open(Lease lease,Call call);
}
