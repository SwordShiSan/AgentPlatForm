package com.agentplatform.api.files;

import com.agentplatform.api.workspace.RunnerControlApi.SignedResult;

public interface FileRunnerApi {
    SignedResult operate(String token,byte[] body);
    SignedResult query(String token,String operationId);
}
