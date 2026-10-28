package com.agentplatform.domain.workspace;

import com.agentplatform.domain.workspace.EnvironmentUseData.Command;
import com.agentplatform.domain.workspace.EnvironmentUseData.Receipt;

public interface WorkspaceShellRunnerPort {
    Receipt executeShell(Command command);
    Receipt queryShell(Command command);
}
