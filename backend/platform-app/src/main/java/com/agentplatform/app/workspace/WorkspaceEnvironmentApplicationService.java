package com.agentplatform.app.workspace;

import com.agentplatform.api.workspace.WorkspaceApi;
import com.agentplatform.api.workspace.WorkspaceEnvironmentApi;
import com.agentplatform.domain.workspace.EnvironmentPreparationRepository;
import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

public final class WorkspaceEnvironmentApplicationService implements WorkspaceEnvironmentApi {
    private final EnvironmentPreparationRepository repository;
    public WorkspaceEnvironmentApplicationService(EnvironmentPreparationRepository repository) {this.repository=repository;}
    private static Actor actor(WorkspaceApi.Caller caller) {
        return new Actor(WorkspaceApplicationService.id(caller.userId()),WorkspaceApplicationService.id(caller.spaceId()));
    }
    private static View view(EnvironmentPreparationRepository.Snapshot snapshot) {
        String phase="NOT_CREATED".equals(snapshot.state())?"NONE":snapshot.state();
        if("STOP".equals(snapshot.operationKind())&&Set.of("PENDING","CLAIMED","RUNNING").contains(snapshot.operationStatus()))phase="STOPPING";
        var actions=snapshot.prepareAllowed()?List.of("PREPARE"):snapshot.retryAllowed()?List.of("RETRY"):List.<String>of();
        return new View(snapshot.workspaceId().toString(),snapshot.state(),phase,snapshot.generation(),snapshot.version(),
                snapshot.operationId()==null?null:snapshot.operationId().toString(),snapshot.errorCode(),actions);
    }
    @Override public View get(WorkspaceApi.Caller caller,String workspaceId) {
        return view(repository.get(actor(caller),WorkspaceApplicationService.id(workspaceId)));
    }
    @Override public Submission prepare(WorkspaceApi.Caller caller,String workspaceId,String requestId,long version,boolean retry) {
        var id=WorkspaceApplicationService.id(workspaceId);
        String hash=RunnerSignatures.sha256(((retry?"RETRY":"PREPARE")+"\n"+id+"\n"+version).getBytes(StandardCharsets.UTF_8));
        var result=repository.prepare(actor(caller),id,WorkspaceApplicationService.id(requestId),hash,version,retry);
        return new Submission(result.operationId().toString(),view(result.environment()));
    }
}
