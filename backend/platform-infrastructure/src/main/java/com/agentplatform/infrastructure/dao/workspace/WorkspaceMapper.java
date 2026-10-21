package com.agentplatform.infrastructure.dao.workspace;

import com.agentplatform.domain.workspace.WorkspaceData.*;
import com.agentplatform.infrastructure.dao.po.WorkspaceRows.*;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkspaceMapper {
    CapacityRow lockCapacity();
    void actorLock(@Param("key") long key);
    boolean authorized(@Param("actor") Actor actor);
    RequestRow request(@Param("actor") Actor actor, @Param("requestId") UUID requestId);
    WorkspaceRow workspace(@Param("spaceId") UUID spaceId, @Param("id") UUID id);
    WorkspaceRow lockWorkspace(@Param("spaceId") UUID spaceId, @Param("id") UUID id);
    List<WorkspaceRow> list(@Param("spaceId") UUID spaceId, @Param("before") UUID before, @Param("limit") int limit);
    int reserveCapacity(@Param("expectedProject") int expectedProject);
    void insertWorkspace(@Param("row") WorkspaceRow row);
    void insertOperation(@Param("id") UUID id, @Param("spaceId") UUID spaceId, @Param("workspaceId") UUID workspaceId,
            @Param("actorId") UUID actorId, @Param("commandId") UUID commandId, @Param("generation") long generation, @Param("fence") long fence);
    void reserveWorkspace(@Param("spaceId") UUID spaceId, @Param("id") UUID id);
    void insertRequest(@Param("actor") Actor actor, @Param("requestId") UUID requestId, @Param("hash") String hash,
            @Param("workspaceId") UUID workspaceId, @Param("operationId") UUID operationId);
    void insertOutbox(@Param("id") UUID id, @Param("spaceId") UUID spaceId, @Param("workspaceId") UUID workspaceId,
            @Param("operationId") UUID operationId, @Param("generation") long generation, @Param("fence") long fence);
    void insertEvent(@Param("id") UUID id, @Param("row") WorkspaceRow row, @Param("payload") String payload);
    OperationRow operation(@Param("id") UUID id);
    OperationRow findOperation(@Param("id") UUID id);
    OperationRow findExpired();
    int expireUnresolved(@Param("id") UUID id);
    int workspaceState(@Param("spaceId") UUID spaceId, @Param("id") UUID id, @Param("state") String state,
            @Param("error") String error, @Param("operationId") UUID operationId, @Param("expectedVersion") long expectedVersion);
    List<PublicationRow> publications(@Param("owner") UUID owner);
    int published(@Param("item") Publication item, @Param("success") boolean success);
    int inbox(@Param("eventId") UUID eventId, @Param("operationId") UUID operationId);
    boolean knownPublication(@Param("item") Publication item);
    int claim(@Param("id") UUID id, @Param("owner") UUID owner);
    boolean current(@Param("command") Command command);
    boolean owned(@Param("command") Command command);
    int renew(@Param("command") Command command);
    int beforeCall(@Param("command") Command command);
    CommandRow runnerCommand(@Param("id") UUID id);
    int accept(@Param("command") Command command, @Param("hash") String hash);
    ReceiptRow receipt(@Param("id") UUID id);
    int commitRunner(@Param("command") Command command, @Param("receipt") Receipt receipt);
    void insertReceipt(@Param("command") Command command, @Param("receipt") Receipt receipt,
            @Param("json") String json, @Param("hash") String hash);
    int complete(@Param("command") Command command, @Param("receipt") Receipt receipt);
    int uncertain(@Param("command") Command command, @Param("code") String code);
    void reservationState(@Param("id") UUID id, @Param("state") String state);
    void quarantine(@Param("id") UUID id, @Param("reason") String reason, @Param("hash") String hash);
}
