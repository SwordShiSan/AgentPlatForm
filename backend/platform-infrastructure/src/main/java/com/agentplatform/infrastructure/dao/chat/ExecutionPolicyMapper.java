package com.agentplatform.infrastructure.dao.chat;

import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ExecutionPolicyMapper {
    record Policy(boolean planMode, String quotaMode, long activeMillis, Instant activeStartedAt) {}
    record LegacyBudget(long tokens, long cost) {}
    LegacyBudget legacyAvailable(@Param("task") UUID task);
    Policy policy(@Param("task") UUID task);
    int setPolicy(@Param("task") UUID task,@Param("plan") boolean plan);
    int beginActivity(@Param("task") UUID task);
    int stopActivity(@Param("task") UUID task);
    int waiting(@Param("conversation") UUID conversation);
    int createAcceptanceRun(@Param("run") UUID run);
    int reserveAcceptance(@Param("run") UUID run);
    int recordAcceptanceCall(@Param("run") UUID run,@Param("call") UUID call);
}
