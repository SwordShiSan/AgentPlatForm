package com.agentplatform.infrastructure.dao.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.McpVersionRepository.*;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface McpVersionMapper {
    boolean authorized(@Param("actor") Actor actor);
    void lock(@Param("actor") Actor actor);
    boolean exists(@Param("actor") Actor actor, @Param("connector") UUID connector);
    List<Version> list(@Param("actor") Actor actor, @Param("connector") UUID connector, @Param("before") Long before, @Param("limit") int limit);
    Version find(@Param("actor") Actor actor, @Param("connector") UUID connector, @Param("version") UUID version, @Param("lock") boolean lock);
    List<Tool> tools(@Param("actor") Actor actor, @Param("connector") UUID connector, @Param("version") UUID version);
    boolean eligible(@Param("actor") Actor actor, @Param("version") Version version, @Param("policy") String policy);
    Activation activation(@Param("actor") Actor actor, @Param("connector") UUID connector);
    int initialize(@Param("actor") Actor actor, @Param("connector") UUID connector);
    int publish(@Param("actor") Actor actor, @Param("version") Version version);
    int setActivation(@Param("actor") Actor actor, @Param("connector") UUID connector, @Param("expected") long expected, @Param("version") UUID version);
    Command accepted(@Param("actor") Actor actor, @Param("request") UUID request);
    int record(@Param("actor") Actor actor, @Param("request") UUID request, @Param("command") Command command);
    int audit(@Param("actor") Actor actor, @Param("id") UUID id, @Param("command") Command command, @Param("trace") UUID trace);
}
