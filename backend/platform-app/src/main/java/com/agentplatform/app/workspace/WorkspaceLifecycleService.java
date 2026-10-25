package com.agentplatform.app.workspace;

import com.agentplatform.api.workspace.WorkspaceApi;
import com.agentplatform.api.workspace.WorkspaceEnvironmentApi;
import com.agentplatform.domain.workspace.EnvironmentLifecycleRepository;
import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.nio.charset.StandardCharsets;

/** User intent only; execution remains in the existing durable outbox/MQ chain. */
public final class WorkspaceLifecycleService implements WorkspaceEnvironmentApi {
    private final EnvironmentLifecycleRepository repository;
    private final WorkspaceEnvironmentApplicationService preparation;

    public WorkspaceLifecycleService(EnvironmentLifecycleRepository repository) {
        this.repository=repository;this.preparation=new WorkspaceEnvironmentApplicationService(repository);
    }
    private static Actor actor(WorkspaceApi.Caller caller) {
        return new Actor(WorkspaceApplicationService.id(caller.userId()),WorkspaceApplicationService.id(caller.spaceId()));
    }
    @Override public View get(WorkspaceApi.Caller caller,String workspaceId) {return preparation.get(caller,workspaceId);}
    @Override public Submission prepare(WorkspaceApi.Caller caller,String workspaceId,String requestId,long version,boolean retry) {
        return preparation.prepare(caller,workspaceId,requestId,version,retry);
    }
    @Override public Submission transition(WorkspaceApi.Caller caller,String workspaceId,String requestId,long version,Action action) {
        var workspace=WorkspaceApplicationService.id(workspaceId);
        String hash=RunnerSignatures.sha256((action.name()+"\n"+workspace+"\n"+version).getBytes(StandardCharsets.UTF_8));
        var result=repository.admit(actor(caller),workspace,WorkspaceApplicationService.id(requestId),hash,version,
                EnvironmentLifecycleRepository.Intent.valueOf(action.name()));
        return new Submission(result.operationId().toString(),get(caller,workspaceId));
    }
    private static OperationView view(EnvironmentLifecycleRepository.Operation result) {
        return new OperationView(result.operationId().toString(),result.workspaceId().toString(),result.intent(),result.status(),
                result.generation(),result.revision(),result.errorCode());
    }
    @Override public OperationView operation(WorkspaceApi.Caller caller,String workspaceId,String operationId) {
        return view(repository.operation(actor(caller),WorkspaceApplicationService.id(workspaceId),WorkspaceApplicationService.id(operationId)));
    }
    @Override public OperationView request(WorkspaceApi.Caller caller,String workspaceId,String requestId) {
        return view(repository.request(actor(caller),WorkspaceApplicationService.id(workspaceId),WorkspaceApplicationService.id(requestId)));
    }
}
