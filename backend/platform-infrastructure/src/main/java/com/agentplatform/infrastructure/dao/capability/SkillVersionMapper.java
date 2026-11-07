package com.agentplatform.infrastructure.dao.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.SkillVersionRepository.Command;
import com.agentplatform.domain.capability.SkillVersionRepository.View;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SkillVersionMapper {
    boolean authorized(@Param("actor") Actor actor);
    void lock(@Param("actor") Actor actor);
    View find(@Param("actor") Actor actor, @Param("capability") UUID capability, @Param("version") UUID version, @Param("lock") boolean lock);
    Command accepted(@Param("actor") Actor actor, @Param("request") UUID request);
    int transition(@Param("actor") Actor actor, @Param("capability") UUID capability, @Param("version") UUID version,
                   @Param("expected") long expected, @Param("from") String from, @Param("to") String to);
    int command(@Param("actor") Actor actor, @Param("command") Command command);
    int audit(@Param("actor") Actor actor, @Param("id") UUID id, @Param("command") Command command, @Param("trace") UUID trace);
}
