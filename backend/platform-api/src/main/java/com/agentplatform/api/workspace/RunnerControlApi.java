package com.agentplatform.api.workspace;

public interface RunnerControlApi {
    record SignedResult(byte[] body, String signature) {}
    SignedResult provision(String token, byte[] body);
    SignedResult query(String token, String operationId);
}
