package com.agentplatform.infrastructure.dao.workspace;

import com.agentplatform.domain.workspace.EnvironmentExecutionData.*;
import com.agentplatform.domain.workspace.RunnerInstance;
import com.agentplatform.infrastructure.dao.po.WorkspaceRows.ReceiptRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EnvironmentExecutionMapper {
    List<Publication> publications(@Param("owner") UUID owner);
    int published(@Param("item") Publication item, @Param("confirmed") boolean confirmed);
    boolean knownPublication(@Param("item") Publication item);
    int inbox(@Param("eventId") UUID eventId, @Param("operationId") UUID operationId);
    Command command(@Param("operationId") UUID operationId);
    int claim(@Param("operationId") UUID operationId, @Param("owner") UUID owner);
    boolean owned(@Param("command") Command command, @Param("active") boolean active);
    int renew(@Param("command") Command command);
    int beforeCall(@Param("command") Command command);
    String commandHash(@Param("commandId") UUID commandId);
    void registerRunner(@Param("runner") RunnerInstance runner);
    RunnerInstance runner(@Param("id") UUID id);
    boolean runnerActive(@Param("id") UUID id);
    boolean runnerMayReconcile(@Param("commandId") UUID commandId,@Param("runnerId") UUID runnerId);
    UUID originalRunner(@Param("commandId") UUID commandId);
    int accept(@Param("command") Command command, @Param("hash") String hash,@Param("runnerId") UUID runnerId);
    ReceiptRow receipt(@Param("commandId") UUID commandId);
    int commitRunner(@Param("command") Command command, @Param("receipt") Receipt receipt,@Param("runnerId") UUID runnerId);
    void insertReceipt(@Param("command") Command command, @Param("receipt") Receipt receipt, @Param("json") String json, @Param("hash") String hash);
    void insertStop(@Param("id") UUID id, @Param("command") Command command, @Param("receipt") Receipt receipt, @Param("hash") String hash,
            @Param("runnerId") UUID runnerId,@Param("priorRunnerProof") UUID priorRunnerProof);
    int completeOperation(@Param("command") Command command, @Param("receipt") Receipt receipt);
    int projectEnvironment(@Param("command") Command command, @Param("receipt") Receipt receipt, @Param("state") String state);
    void projectWorkspace(@Param("command") Command command, @Param("state") String state);
    int releaseSlot(@Param("command") Command command);
    int uncertain(@Param("command") Command command, @Param("code") String code);
    int rejectBeforeClaim(@Param("operationId") UUID operationId);
    void projectRejected(@Param("environmentId") UUID environmentId);
}
