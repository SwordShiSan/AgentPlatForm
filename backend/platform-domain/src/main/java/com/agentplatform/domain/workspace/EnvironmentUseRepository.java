package com.agentplatform.domain.workspace;

import com.agentplatform.domain.workspace.EnvironmentUseData.*;
import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import java.util.UUID;

/** Protected application/runner data plane; not a public file or command execution API. */
public interface EnvironmentUseRepository {
    Admission admit(Actor actor,UUID workspaceId,UUID requestId,UUID ownerId,Request request);
    default Admission admitShell(Actor actor,UUID workspaceId,UUID requestId,UUID ownerId,WorkspaceShellData.Request request) {
        throw new WorkspaceFailure(422,"WORKSPACE_SHELL_NOT_AVAILABLE");
    }
    Command lookup(UUID useId);
    Command lookup(Actor actor,UUID useId);
    void renew(Command command);
    boolean accept(Command command,UUID runnerInstanceId);
    void check(Command command,UUID runnerInstanceId);
    void checkOwnership(Command command);
    void checkStopObservation(Command command,UUID runnerInstanceId);
    void checkCompletion(Command command);
    boolean cancelled(Command command);
    void cancel(Actor actor,UUID useId);
    boolean stopCommitted(UUID useId);
    void recordExec(Command command,UUID runnerInstanceId,String containerId,String execId);
    void commitFact(Command command,UUID runnerInstanceId,Fact fact);
    Receipt receipt(Command command);
    Fact committedFact(Command command);
    Fact result(Actor actor,UUID useId);
    void complete(Command command);
    void uncertain(Command command,String code);
}
