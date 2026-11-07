package com.agentplatform.infrastructure.dao.capability;

import com.agentplatform.domain.capability.SkillImportRepository.Actor;
import com.agentplatform.infrastructure.dao.po.SkillImportRow;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SkillImportMapper {
    boolean authorized(@Param("actor") Actor actor);
    void lock(@Param("actor") Actor actor);
    UUID lockQuota(@Param("actor") Actor actor);
    boolean existingSkill(@Param("actor") Actor actor, @Param("capability") UUID capability);
    SkillImportRow find(@Param("actor") Actor actor, @Param("id") UUID id);
    SkillImportRow findRequest(@Param("actor") Actor actor, @Param("requestId") UUID requestId);
    int reserve(@Param("actor") Actor actor, @Param("bytes") long bytes);
    int insert(@Param("actor") Actor actor, @Param("id") UUID id, @Param("requestId") UUID requestId,
               @Param("requestHash") String requestHash, @Param("capability") UUID capability,
               @Param("version") UUID version, @Param("asset") UUID asset, @Param("bytes") long bytes,
               @Param("sha") String sha, @Param("key") String key);
    int reservation(@Param("actor") Actor actor, @Param("id") UUID id, @Param("bytes") long bytes);
    int begin(@Param("actor") Actor actor, @Param("id") UUID id, @Param("owner") UUID owner);
    int guard(@Param("actor") Actor actor, @Param("id") UUID id, @Param("owner") UUID owner);
    int finish(@Param("actor") Actor actor, @Param("id") UUID id, @Param("owner") UUID owner,
               @Param("state") String state);
    int reconcileReservation(@Param("actor") Actor actor, @Param("id") UUID id);
    int queue(@Param("actor") Actor actor, @Param("id") UUID id);
    int outbox(@Param("actor") Actor actor, @Param("id") UUID id, @Param("event") UUID event);
    UUID completion(@Param("actor") Actor actor, @Param("requestId") UUID requestId);
    int recordCompletion(@Param("actor") Actor actor, @Param("id") UUID id, @Param("requestId") UUID requestId);
}
