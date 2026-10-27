package com.agentplatform.infrastructure.dao.chat;

import com.agentplatform.infrastructure.dao.po.WorkspaceToolRows.*;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkspaceToolMapper {
    com.agentplatform.domain.chat.WorkspaceToolLedger.FileAdmission fileAdmission(@Param("operation")UUID operation);
    int insertFileAdmission(@Param("lease")com.agentplatform.domain.chat.ChatData.Lease lease,@Param("operation")UUID operation,@Param("upload")UUID upload,@Param("failure")String failure);
    int bindFileOperation(@Param("operation")UUID operation,@Param("fileOperation")UUID fileOperation,@Param("id")UUID id);
    boolean fileStopped(@Param("lease")com.agentplatform.domain.chat.ChatData.Lease lease,@Param("call")String call);
    Catalog catalog(@Param("space") UUID space,@Param("task") UUID task);
    int freezeCatalog(@Param("space") UUID space,@Param("task") UUID task,@Param("actor") UUID actor,
                      @Param("workspace") UUID workspace,@Param("version") String version,@Param("bundle") UUID bundle);
    Resource resource(@Param("space") UUID space,@Param("workspace") UUID workspace,@Param("actor") UUID actor);
    Intent intent(@Param("space") UUID space,@Param("task") UUID task,@Param("operation") UUID operation);
    int insertIntent(@Param("row") Intent row);
    int acceptPlan(@Param("space") UUID space,@Param("task") UUID task,@Param("revision") UUID revision,@Param("answer") UUID answer);
    int hasAcceptedCurrentPlan(@Param("space") UUID space,@Param("task") UUID task);
    UUID lockObjectQuota(@Param("space") UUID space);
    UUID lockWorkspace(@Param("space") UUID space,@Param("workspace") UUID workspace,@Param("actor") UUID actor);
    String targetById(@Param("space") UUID space,@Param("workspace") UUID workspace,@Param("file") UUID file,@Param("version") UUID version);
    String targetByPath(@Param("space") UUID space,@Param("workspace") UUID workspace,@Param("path") String path);
    UUID operationId(@Param("space") UUID space,@Param("task") UUID task,@Param("call") String call);
    String currentResourceHash(@Param("actor") UUID actor,@Param("space") UUID space,@Param("task") UUID task,@Param("operation") UUID operation);
}
