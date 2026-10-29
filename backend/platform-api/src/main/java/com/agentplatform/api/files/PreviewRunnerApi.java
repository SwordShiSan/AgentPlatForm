package com.agentplatform.api.files;

import com.agentplatform.api.workspace.RunnerControlApi.SignedResult;

public interface PreviewRunnerApi {
    SignedResult operate(String token,byte[] body);
    SignedResult query(String token,String previewId);
}
