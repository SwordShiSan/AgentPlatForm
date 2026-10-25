package com.agentplatform.api.workspace;

public interface EnvironmentRunnerApi {
    RunnerControlApi.SignedResult operate(String token,byte[] body);
    RunnerControlApi.SignedResult query(String token,String operationId);
    default RunnerControlApi.SignedResult operateV3(String token,byte[] body) {
        throw new UnsupportedOperationException("ENVIRONMENT_V3 is unavailable");
    }
    default RunnerControlApi.SignedResult queryV3(String token,String operationId) {
        throw new UnsupportedOperationException("ENVIRONMENT_V3 is unavailable");
    }
}
