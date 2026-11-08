package com.agentplatform.infrastructure.dao.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.McpConnectorRepository.Configuration;
import com.agentplatform.domain.capability.McpConnectorRepository.Probe;
import com.agentplatform.infrastructure.dao.po.McpConnectorRequestRow;
import java.util.UUID;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface McpConnectorMapper {
    boolean authorized(@Param("actor") Actor actor);
    void lock(@Param("actor") Actor actor);
    boolean activeCredential(@Param("actor") Actor actor, @Param("id") UUID id, @Param("revision") long revision);
    Configuration find(@Param("actor") Actor actor, @Param("id") UUID id, @Param("lock") boolean lock);
    List<Configuration> list(@Param("actor") Actor actor, @Param("after") UUID after, @Param("limit") int limit);
    McpConnectorRequestRow accepted(@Param("actor") Actor actor, @Param("request") UUID request);
    int insertCapability(@Param("actor") Actor actor, @Param("config") Configuration config);
    int insert(@Param("actor") Actor actor, @Param("config") Configuration config);
    int update(@Param("actor") Actor actor, @Param("config") Configuration config, @Param("expected") long expected);
    int record(@Param("actor") Actor actor, @Param("request") UUID request, @Param("hash") String hash,
               @Param("config") Configuration config, @Param("snapshot") String snapshot);
    int audit(@Param("actor") Actor actor, @Param("id") UUID id, @Param("action") String action,
              @Param("resource") UUID resource, @Param("trace") UUID trace);
    Probe probeRequest(@Param("actor") Actor actor, @Param("request") UUID request);
    String probeRequestHash(@Param("actor") Actor actor, @Param("request") UUID request);
    Probe probe(@Param("actor") Actor actor, @Param("id") UUID id);
    int insertProbe(@Param("actor") Actor actor, @Param("id") UUID id, @Param("request") UUID request,
                    @Param("hash") String hash, @Param("config") Configuration config, @Param("policy") String policy, @Param("trace") UUID trace);
    int outbox(@Param("actor") Actor actor, @Param("id") UUID id, @Param("event") UUID event);
}
