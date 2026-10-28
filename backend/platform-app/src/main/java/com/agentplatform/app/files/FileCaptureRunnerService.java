package com.agentplatform.app.files;

import com.agentplatform.api.files.FileCaptureRunnerApi;
import com.agentplatform.api.workspace.RunnerControlApi.SignedResult;
import com.agentplatform.domain.files.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;

/** Separate signed wire family over the existing file acceptance/receipt authority. */
public final class FileCaptureRunnerService implements FileCaptureRunnerApi {
    private final FileRunnerService delegate;
    public FileCaptureRunnerService(FileRunnerRepository repository,FileRuntime runtime,AssetStore assets,RunnerSignatures signatures) {
        delegate=new FileRunnerService(repository,runtime,assets,signatures,true);
    }
    @Override public SignedResult operate(String token,byte[] body){return delegate.operate(token,body);}
    @Override public SignedResult query(String token,String operationId){return delegate.query(token,operationId);}
}
