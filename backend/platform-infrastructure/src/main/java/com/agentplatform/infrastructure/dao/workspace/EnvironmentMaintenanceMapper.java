package com.agentplatform.infrastructure.dao.workspace;

import com.agentplatform.domain.workspace.EnvironmentExecutionData.Command;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EnvironmentMaintenanceMapper {
    UUID expiredOperation();
    int blockExpired(@Param("operationId") UUID operationId);
    UUID readyOperation();
    boolean idle(@Param("environmentId") UUID environmentId);
    boolean fileOperationBusy(@Param("spaceId") UUID spaceId,@Param("workspaceId") UUID workspaceId);
    void insertStop(@Param("previous") Command previous,@Param("operationId") UUID operationId,
            @Param("commandId") UUID commandId,@Param("fence") long fence);
    int selectStop(@Param("previous") Command previous,@Param("operationId") UUID operationId,@Param("fence") long fence,@Param("currentFence") long currentFence);
}
