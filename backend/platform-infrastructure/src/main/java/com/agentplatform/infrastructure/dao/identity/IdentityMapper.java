package com.agentplatform.infrastructure.dao.identity;

import com.agentplatform.infrastructure.dao.po.IdentityRows.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IdentityMapper {
    void lock(@Param("key") long key);
    OperationRow operation(@Param("request") UUID request);
    boolean bootstrapConsumed();
    int insertUser(@Param("id") UUID id, @Param("name") String name, @Param("hash") String hash, @Param("bootstrap") boolean bootstrap);
    int insertSpace(@Param("space") UUID space, @Param("user") UUID user);
    int insertMembership(@Param("space") UUID space, @Param("user") UUID user);
    int insertStorage(@Param("space") UUID space);
    int insertOperation(@Param("request") UUID request, @Param("fingerprint") String fingerprint, @Param("user") UUID user, @Param("bootstrap") boolean bootstrap);
    UserRow findUser(@Param("name") String name);
    UserRow user(@Param("id") UUID id);
    List<SpaceRow> spaces(@Param("user") UUID user);
    SessionRow session(@Param("token") String token, @Param("now") Instant now, @Param("idle") Instant idle);
    int touchSession(@Param("token") String token, @Param("now") Instant now);
    int insertSession(@Param("id") UUID id, @Param("token") String token, @Param("csrf") String csrf, @Param("user") UUID user, @Param("created") Instant created, @Param("seen") Instant seen, @Param("expires") Instant expires);
    int pruneSessions(@Param("now") Instant now, @Param("retention") Instant retention);
    int pruneRates(@Param("retention") Instant retention, @Param("now") Instant now);
    int csrf(@Param("token") String token, @Param("csrf") String csrf);
    UUID consumeSession(@Param("token") String token, @Param("now") Instant now, @Param("idle") Instant idle);
    int revoke(@Param("token") String token, @Param("now") Instant now);
    int disable(@Param("id") UUID id);
    int password(@Param("id") UUID id, @Param("hash") String hash);
    int revokeUser(@Param("id") UUID id, @Param("now") Instant now);
    int rate(@Param("key") String key, @Param("now") Instant now, @Param("boundary") Instant boundary);
    RateRow rateRow(@Param("key") String key);
    int ensureRate(@Param("key") String key, @Param("now") Instant now);
    RateRow lockRate(@Param("key") String key);
    int accountResult(@Param("key") String key, @Param("attempts") int attempts, @Param("start") Instant start, @Param("locked") Instant locked);
    boolean ready();
}
