package com.agentplatform.api.workspace;

public interface EnvironmentRunnerApi {
    RunnerControlApi.SignedResult operate(String token,byte[] body);
    RunnerControlApi.SignedResult query(String token,String operationId);
}
