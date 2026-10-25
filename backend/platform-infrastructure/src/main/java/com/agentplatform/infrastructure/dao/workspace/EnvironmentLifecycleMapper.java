package com.agentplatform.infrastructure.dao.workspace;

import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import com.agentplatform.infrastructure.dao.po.EnvironmentLifecycleRows.*;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EnvironmentLifecycleMapper {
    Target target(@Param("environmentId") UUID environmentId);
    ActiveUse activeUse(@Param("environmentId") UUID environmentId);
    boolean fileOperationBusy(@Param("spaceId") UUID spaceId, @Param("workspaceId") UUID workspaceId);
    Admission original(@Param("actor") Actor actor, @Param("requestId") UUID requestId);
    Plan pending(@Param("operationId") UUID operationId);
    int cancelSandboxUse(@Param("id") UUID id);
    int activate(@Param("operationId") UUID operationId, @Param("fence") long fence,
                 @Param("priorStopId") UUID priorStopId);
    int selectPending(@Param("row") Plan row, @Param("expectedVersion") long expectedVersion,
                      @Param("phase") String phase);
    void insertOperation(@Param("row") Plan row);
    int selectOperation(@Param("row") Plan row, @Param("expectedVersion") long expectedVersion,
                        @Param("phase") String phase);
    void projectWorkspace(@Param("row") Plan row, @Param("phase") String phase);
}
