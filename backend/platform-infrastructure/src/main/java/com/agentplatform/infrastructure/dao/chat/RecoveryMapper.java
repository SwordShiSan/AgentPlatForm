package com.agentplatform.infrastructure.dao.chat;

import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.domain.chat.RecoveryLedger.RestoreCommand;
import com.agentplatform.infrastructure.dao.po.RecoveryRows.*;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RecoveryMapper {
    TaskRow task(@Param("id") UUID id);
    CheckpointRow latest(@Param("task") UUID task);
    CheckpointMeta latestMetadata(@Param("task") UUID task);
    CheckpointRow checkpoint(@Param("id") UUID id, @Param("task") UUID task);
    RequestRow request(@Param("command") UUID command);
    RequestRow latestRequest(@Param("task") UUID task);
    RequestRow attemptRequest(@Param("attempt") UUID attempt);
    UUID latestAttempt(@Param("task") UUID task);
    int hasStop(@Param("task") UUID task, @Param("attempt") UUID attempt);
    int live(@Param("task") UUID task);
    int newerTasks(@Param("task") UUID task);
    int unknown(@Param("task") UUID task);
    int calls(@Param("task") UUID task);
    Instant now();
    int initializeDeadline(@Param("task") UUID task);
    String facts(@Param("task") UUID task);
    List<TurnRow> turns(@Param("task") UUID task);
    List<OperationRow> operations(@Param("task") UUID task);
    int insertCheckpoint(@Param("lease") Lease lease, @Param("id") UUID id, @Param("bundle") UUID bundle,
        @Param("bundleHash") String bundleHash, @Param("phase") String phase, @Param("manifest") String manifest,
        @Param("manifestHash") String manifestHash, @Param("canonical") String canonical, @Param("canonicalHash") String canonicalHash,
        @Param("states") String states, @Param("stateHash") String stateHash, @Param("watermark") long watermark);
    int reserve(@Param("space") UUID space, @Param("user") UUID user, @Param("tokens") long tokens, @Param("cost") long cost);
    int queueTask(@Param("task") UUID task, @Param("revision") long revision, @Param("tokens") long tokens, @Param("cost") long cost);
    int queueDispatch(@Param("task") UUID task);
    long generation(@Param("task") UUID task);
    int insertRequest(@Param("command") RestoreCommand command, @Param("space") UUID space, @Param("revision") long revision, @Param("hash") String hash);
    int claimRequest(@Param("command") UUID command, @Param("attempt") UUID attempt);
    int finishRequest(@Param("attempt") UUID attempt, @Param("success") boolean success);
    int rejectRequest(@Param("command") UUID command);
    int rejectMessage(@Param("task") UUID task);
    int bindTurn(@Param("lease") Lease lease, @Param("turn") UUID turn);
    int bindOperation(@Param("lease") Lease lease, @Param("operation") UUID operation);
    int boundTurn(@Param("lease") Lease lease, @Param("turn") UUID turn);
    int boundOperation(@Param("lease") Lease lease, @Param("call") String call);
}
