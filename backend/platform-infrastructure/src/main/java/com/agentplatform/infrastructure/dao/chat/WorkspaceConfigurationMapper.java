package com.agentplatform.infrastructure.dao.chat;

import com.agentplatform.domain.chat.WorkspaceConfigurationRepository.Actor;
import com.agentplatform.domain.chat.WorkspaceConfigurationRepository.Patch;
import com.agentplatform.infrastructure.dao.po.WorkspaceConfigurationRow;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkspaceConfigurationMapper {
    void lockUser(@Param("actor") Actor actor);
    void lockWorkspace(@Param("actor") Actor actor, @Param("workspaceId") UUID workspaceId);
    boolean authorized(@Param("actor") Actor actor, @Param("workspaceId") UUID workspaceId);
    boolean modelEnabled(@Param("modelId") UUID modelId);
    WorkspaceConfigurationRow find(@Param("actor") Actor actor, @Param("workspaceId") UUID workspaceId);
    int initialize(@Param("actor") Actor actor, @Param("workspaceId") UUID workspaceId);
    int update(@Param("actor") Actor actor, @Param("workspaceId") UUID workspaceId,
               @Param("expected") long expected, @Param("patch") Patch patch);
    int audit(@Param("id") UUID id, @Param("actor") Actor actor,
              @Param("workspaceId") UUID workspaceId, @Param("traceId") UUID traceId);
}
