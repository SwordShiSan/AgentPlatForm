package com.agentplatform.api.workspace;

public interface EnvironmentUseRunnerApi {
    RunnerControlApi.SignedResult operate(String token,byte[] body);
    RunnerControlApi.SignedResult query(String token,String useId);
}
