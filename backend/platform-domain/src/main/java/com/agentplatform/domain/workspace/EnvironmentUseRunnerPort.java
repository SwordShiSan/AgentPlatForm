package com.agentplatform.domain.workspace;

import com.agentplatform.domain.workspace.EnvironmentUseData.Command;
import com.agentplatform.domain.workspace.EnvironmentUseData.Receipt;

/** Signed internal control only; never a public user file/program API. */
public interface EnvironmentUseRunnerPort {
    Receipt executeUse(Command command);
    Receipt queryUse(Command command);
}
