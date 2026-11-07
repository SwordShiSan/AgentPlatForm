package com.agentplatform.infrastructure.dao.capability;

import com.agentplatform.domain.capability.SkillValidationRepository.Command;
import com.agentplatform.domain.capability.SkillValidationRepository.Publication;
import com.agentplatform.infrastructure.dao.po.SkillWorkerRow;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SkillWorkerMapper {
    Publication next(@Param("owner") UUID owner);
    int published(@Param("publication") Publication publication);
    SkillWorkerRow byPublication(@Param("publication") Publication publication);
    SkillWorkerRow byCommand(@Param("command") Command command);
    boolean authorized(@Param("actor") UUID actor, @Param("space") UUID space);
    UUID lockQuota(@Param("space") UUID space);
    int inbox(@Param("publication") Publication publication);
    int blockQueued(@Param("publication") Publication publication);
    int reconcileQueued(@Param("publication") Publication publication);
    int claim(@Param("publication") Publication publication, @Param("owner") UUID owner, @Param("fence") long fence);
    int guard(@Param("command") Command command);
    int block(@Param("command") Command command, @Param("state") String state, @Param("reason") String reason);
    int reconciling(@Param("command") Command command);
    Integer lockCapability(@Param("space") UUID space, @Param("actor") UUID actor, @Param("id") UUID id);
    int insertCapability(@Param("row") SkillWorkerRow row);
    long nextVersion(@Param("row") SkillWorkerRow row);
    int insertVersion(@Param("row") SkillWorkerRow row, @Param("number") long number, @Param("hash") String hash);
    int insertAsset(@Param("row") SkillWorkerRow row);
    int insertPackage(@Param("row") SkillWorkerRow row, @Param("name") String name, @Param("description") String description,
                      @Param("expanded") long expanded, @Param("fileCount") int fileCount, @Param("manifest") String manifest,
                      @Param("dependencies") String dependencies);
    int insertStatus(@Param("row") SkillWorkerRow row);
    int settleQuota(@Param("command") Command command);
    int settleReservation(@Param("command") Command command);
    int finish(@Param("command") Command command);
    int audit(@Param("command") Command command, @Param("id") UUID id);
    int quarantine(@Param("id") UUID id, @Param("hash") String hash);
}
