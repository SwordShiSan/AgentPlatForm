package com.agentplatform.infrastructure.dao.chat;

import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.infrastructure.dao.po.ApprovalRows.*;
import com.agentplatform.infrastructure.dao.po.PermissionRows.PermissionRow;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ApprovalMapper {
    OperationRow operation(@Param("lease") Lease lease, @Param("call") String call);
    OperationRow original(@Param("space") UUID space, @Param("task") UUID task, @Param("id") UUID id);
    BindingRow binding(@Param("space") UUID space, @Param("task") UUID task, @Param("call") String call);
    BindingRow interaction(@Param("id") UUID id);
    String resource(@Param("p") PermissionRow permission);
    String lockResource(@Param("p") PermissionRow permission);
    int bind(@Param("id") UUID interaction, @Param("lease") Lease lease, @Param("operation") OperationRow operation,
             @Param("p") PermissionRow permission, @Param("resource") String resourceHash,
             @Param("fingerprint") String fingerprint, @Param("summary") String summary, @Param("reason") String reason);
    int deniedFingerprint(@Param("space") UUID space, @Param("task") UUID task, @Param("fingerprint") String fingerprint);
    ConsumptionRow consumption(@Param("operation") UUID operation);
    int consume(@Param("lease") Lease lease, @Param("binding") BindingRow binding, @Param("answer") UUID answer,
                @Param("outcome") String outcome);
    int deny(@Param("lease") Lease lease, @Param("operation") UUID operation, @Param("result") String result, @Param("hash") String hash);
}
