package com.agentplatform.domain.workspace;

import com.agentplatform.domain.workspace.WorkspaceData.*;

public interface RunnerControlPort {
    Receipt provision(Command command);
    Receipt query(Command command);
}
