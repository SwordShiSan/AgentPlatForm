package com.agentplatform.infrastructure.dao.chat;

import com.agentplatform.infrastructure.dao.po.WorkspaceToolRows.*;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkspaceToolMapper {
    Catalog catalog(@Param("space") UUID space,@Param("task") UUID task);
    int freezeCatalog(@Param("space") UUID space,@Param("task") UUID task,@Param("actor") UUID actor,
                      @Param("workspace") UUID workspace,@Param("version") String version,@Param("bundle") UUID bundle);
    Resource resource(@Param("space") UUID space,@Param("workspace") UUID workspace,@Param("actor") UUID actor);
    Intent intent(@Param("space") UUID space,@Param("task") UUID task,@Param("operation") UUID operation);
    int insertIntent(@Param("row") Intent row);
    int acceptPlan(@Param("space") UUID space,@Param("task") UUID task,@Param("revision") UUID revision,@Param("answer") UUID answer);
    int hasAcceptedCurrentPlan(@Param("space") UUID space,@Param("task") UUID task);
}
