package com.agentplatform.infrastructure.dao.workspace;

import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import com.agentplatform.infrastructure.dao.po.EnvironmentRows.*;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EnvironmentPreparationMapper {
    Slot lockSlot();
    Environment environment(@Param("spaceId") UUID spaceId, @Param("workspaceId") UUID workspaceId);
    Environment lockEnvironment(@Param("spaceId") UUID spaceId, @Param("workspaceId") UUID workspaceId);
    Operation operation(@Param("id") UUID id);
    Request request(@Param("actor") Actor actor, @Param("requestId") UUID requestId);
    boolean unresolvedUse(@Param("environmentId") UUID environmentId);
    boolean confirmedStop(@Param("operationId") UUID operationId);
    void insertEnvironment(@Param("row") Environment row);
    int replaceGeneration(@Param("row") Environment row, @Param("expectedVersion") long expectedVersion);
    void insertOperation(@Param("row") Environment row, @Param("actorId") UUID actorId);
    int reserveSlot(@Param("row") Environment row, @Param("revision") long revision);
    void insertRequest(@Param("actor") Actor actor, @Param("requestId") UUID requestId,
            @Param("hash") String hash, @Param("row") Environment row);
    void insertOutbox(@Param("id") UUID id, @Param("row") Environment row);
    void projectWorkspace(@Param("spaceId") UUID spaceId, @Param("workspaceId") UUID workspaceId);
    void insertEvent(@Param("id") UUID id, @Param("row") Environment row, @Param("payload") String payload);
}
