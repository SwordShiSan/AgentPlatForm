package com.agentplatform.domain.workspace;

import com.agentplatform.domain.workspace.EnvironmentExecutionData.*;

/** Signed internal environment lifecycle; no caller-selected endpoint or program. */
public interface EnvironmentRunnerPort {
    Receipt prepareEnvironment(Command command);
    Receipt queryEnvironment(Command command);
}
