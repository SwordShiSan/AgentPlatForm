package com.agentplatform.api.workspace;

/** Internal signed transport; no browser route or arbitrary endpoint selection. */
public interface WorkspaceShellRunnerApi {
    RunnerControlApi.SignedResult operate(String token,byte[] body);
    RunnerControlApi.SignedResult query(String token,String useId);
}
