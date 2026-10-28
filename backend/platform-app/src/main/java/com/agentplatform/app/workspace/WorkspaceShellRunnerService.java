package com.agentplatform.app.workspace;

import com.agentplatform.api.workspace.WorkspaceShellRunnerApi;
import com.agentplatform.api.workspace.RunnerControlApi.SignedResult;
import com.agentplatform.domain.workspace.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;

/** A separate versioned wire family over the canonical use/stop ledger. */
public final class WorkspaceShellRunnerService implements WorkspaceShellRunnerApi {
    public static final String PATH="/internal/workspace-shell/v1";
    private final EnvironmentUseRunnerService delegate;
    public WorkspaceShellRunnerService(EnvironmentUseRepository repository,RunnerSignatures signatures,SandboxRuntime runtime,
            RunnerInstance instance,WorkspaceEffectBoundary boundary) {
        delegate=new EnvironmentUseRunnerService(repository,signatures,runtime,instance,boundary,null,true);
    }
    @Override public SignedResult operate(String token,byte[] body){return delegate.operate(token,body);}
    @Override public SignedResult query(String token,String id){return delegate.query(token,id);}
}
