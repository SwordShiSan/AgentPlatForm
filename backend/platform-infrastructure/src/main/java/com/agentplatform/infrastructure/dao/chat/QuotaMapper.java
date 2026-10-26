package com.agentplatform.infrastructure.dao.chat;

import com.agentplatform.infrastructure.dao.po.QuotaRows.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface QuotaMapper {
    int ensurePrincipal(@Param("user") UUID user);
    Principal lockPrincipal(@Param("user") UUID user);
    List<Policy> policies(@Param("user") UUID user);
    int ensureBucket(@Param("user") UUID user,@Param("metric") String metric,@Param("start") Instant start,@Param("end") Instant end);
    Bucket bucket(@Param("user") UUID user,@Param("metric") String metric,@Param("start") Instant start);
    long earlierOutstanding(@Param("user") UUID user,@Param("metric") String metric,@Param("start") Instant start);
    List<Reservation> reservations(@Param("call") UUID call);
    int insertReservation(@Param("call") UUID call,@Param("metric") String metric,@Param("user") UUID user,
                          @Param("start") Instant start,@Param("policy") UUID policy,@Param("revision") long revision,
                          @Param("amount") long amount,@Param("unlimited") boolean unlimited);
    int reserveBucket(@Param("user") UUID user,@Param("metric") String metric,@Param("start") Instant start,@Param("amount") long amount);
    int settleReservation(@Param("call") UUID call,@Param("metric") String metric,@Param("actual") long actual,@Param("state") String state);
    int settleBucket(@Param("user") UUID user,@Param("metric") String metric,@Param("start") Instant start,@Param("reserved") long reserved,@Param("actual") long actual);
    int unknown(@Param("call") UUID call);
    Policy policy(@Param("user") UUID user,@Param("metric") String metric);
    int insertPolicy(@Param("id") UUID id,@Param("change") com.agentplatform.domain.chat.QuotaData.Change change);
    int changePolicy(@Param("id") UUID id,@Param("change") com.agentplatform.domain.chat.QuotaData.Change change);
    record Audit(String requestHash,String afterJson) {}
    Audit policyCommand(@Param("command") UUID command);
    int auditPolicy(@Param("id") UUID id,@Param("change") com.agentplatform.domain.chat.QuotaData.Change change,@Param("hash") String hash,@Param("before") String before,@Param("after") String after);
}
