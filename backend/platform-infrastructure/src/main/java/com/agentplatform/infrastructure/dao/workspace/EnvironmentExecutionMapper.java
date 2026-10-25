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
    UUID observableOperation();
    com.agentplatform.infrastructure.dao.po.EnvironmentLifecycleRows.ObservationLease observe(
            @Param("command") Command command,@Param("observerId") UUID observerId);
    boolean observing(@Param("command") Command command,@Param("observerId") UUID observerId,
                      @Param("revision") Long revision,@Param("active") boolean active);
    int completeObservedOperation(@Param("observation") Observation observation,@Param("receipt") Receipt receipt);
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
    boolean receiptPointerValid(@Param("commandId") UUID commandId,@Param("revision") long revision,@Param("state") String state);
    com.agentplatform.domain.workspace.SandboxRuntime.Stop priorStop(@Param("command") Command command);
    int commitRunner(@Param("command") Command command, @Param("receipt") Receipt receipt,@Param("runnerId") UUID runnerId);
    void insertReceipt(@Param("command") Command command, @Param("receipt") Receipt receipt, @Param("json") String json, @Param("hash") String hash);
    void insertStop(@Param("id") UUID id, @Param("command") Command command, @Param("receipt") Receipt receipt, @Param("hash") String hash,
            @Param("runnerId") UUID runnerId,@Param("priorRunnerProof") UUID priorRunnerProof);
    UUID stopReceiptId(@Param("command") Command command);
    boolean cleanupConfirmed(@Param("command") Command command);
    void insertCleanup(@Param("id") UUID id, @Param("command") Command command,
                       @Param("stopId") UUID stopId, @Param("runnerId") UUID runnerId,
                       @Param("removal") com.agentplatform.domain.workspace.SandboxRuntime.Removal removal,
                       @Param("json") String json, @Param("hash") String hash);
    int commitCleanup(@Param("command") Command command, @Param("runnerId") UUID runnerId);
    int completeOperation(@Param("command") Command command, @Param("receipt") Receipt receipt);
    int projectEnvironment(@Param("command") Command command, @Param("receipt") Receipt receipt, @Param("state") String state);
    void projectWorkspace(@Param("command") Command command, @Param("state") String state);
    int releaseSlot(@Param("command") Command command);
    int uncertain(@Param("command") Command command, @Param("code") String code);
    int rejectBeforeClaim(@Param("operationId") UUID operationId);
    void projectRejected(@Param("environmentId") UUID environmentId);
}
