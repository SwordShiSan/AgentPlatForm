package com.agentplatform.infrastructure.dao.workspace;

import com.agentplatform.domain.workspace.EnvironmentUseData.Command;
import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import com.agentplatform.infrastructure.dao.po.EnvironmentUseRows.*;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EnvironmentUseMapper {
    boolean shellAdmissionAllowed(@Param("actor")Actor actor,@Param("workspace")UUID workspace,@Param("request")String request);
    boolean shellParentAllowed(@Param("id")UUID id,@Param("locks")boolean locks);
    int insertShellAdmission(@Param("row")Use row,@Param("parent")com.agentplatform.domain.workspace.WorkspaceShellData.Parent parent);
    int bindShellJob(@Param("row")Use row,@Param("parent")com.agentplatform.domain.workspace.WorkspaceShellData.Parent parent,@Param("id")UUID id);
    Use use(@Param("id") UUID id);
    Use lockUse(@Param("id") UUID id);
    Use request(@Param("actor") Actor actor,@Param("requestId") UUID requestId);
    Physical container(@Param("environmentId") UUID environmentId);
    int advanceFence(@Param("spaceId") UUID spaceId,@Param("workspaceId") UUID workspaceId,@Param("environmentId") UUID environmentId,
            @Param("fence") long fence,@Param("version") long version);
    void insertUse(@Param("row") Use row);
    boolean owned(@Param("command") Command command,@Param("executing") boolean executing,@Param("active") boolean active);
    boolean stoppedCompletion(@Param("command") Command command);
    int renew(@Param("command") Command command);
    int accept(@Param("command") Command command,@Param("runnerId") UUID runnerId);
    int trustedFileMode(@Param("command") Command command,@Param("runnerId") UUID runnerId);
    int trustedFileExec(@Param("command") Command command,@Param("runnerId") UUID runnerId,@Param("execId") String execId);
    String fileMode(@Param("id") UUID id);
    int recordExec(@Param("command") Command command,@Param("runnerId") UUID runnerId,@Param("execId") String execId);
    Result result(@Param("id") UUID id);
    void insertResult(@Param("id") UUID id,@Param("row") Result row);
    int finish(@Param("command") Command command,@Param("state") String state,@Param("outcome") String outcome,@Param("error") String error);
    int uncertain(@Param("command") Command command,@Param("error") String error);
    int projectBlocked(@Param("command") Command command,@Param("error") String error);
    void projectWorkspaceBlocked(@Param("command") Command command);
    void touchEnvironment(@Param("command") Command command);
    int cancel(@Param("actor") Actor actor,@Param("id") UUID id);
    boolean runnerMayReconcile(@Param("id") UUID id,@Param("runnerId") UUID runnerId);
    int bindStopObserver(@Param("id") UUID id,@Param("runnerId") UUID runnerId);
    boolean validStop(@Param("stop") com.agentplatform.domain.workspace.SandboxRuntime.Stop stop);
    void projectWorkspaceStopped(@Param("command") Command command);
    int releaseSlot(@Param("command") Command command);
    void insertStopFactOperation(@Param("id") UUID id,@Param("command") Command command,@Param("ownerId") UUID ownerId,@Param("fence") long fence);
    int projectStopped(@Param("command") Command command,@Param("operationId") UUID operationId,@Param("fence") long fence,@Param("error") String error);
    int closeStopped(@Param("command") Command command,@Param("operationId") UUID operationId,@Param("outcome") String outcome,@Param("error") String error);
    void insertUseStopReceipt(@Param("id") UUID id,@Param("command") Command command,@Param("operationId") UUID operationId,@Param("fence") long fence,
            @Param("ownerId") UUID ownerId,@Param("fact") com.agentplatform.domain.workspace.EnvironmentUseData.Fact fact,@Param("hash") String hash,@Param("priorProof") UUID priorProof);
}
