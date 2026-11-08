package com.agentplatform.infrastructure.dao.capability;

import com.agentplatform.domain.capability.ConnectorCiphertext;
import com.agentplatform.domain.capability.McpProbeRepository.Command;
import com.agentplatform.domain.capability.McpProbeRepository.Publication;
import com.agentplatform.domain.capability.McpProbeRepository.Tool;
import com.agentplatform.infrastructure.dao.po.McpProbeRow;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface McpProbeMapper {
    Publication next(@Param("owner") UUID owner);
    int published(@Param("publication") Publication publication);
    McpProbeRow byPublication(@Param("publication") Publication publication);
    McpProbeRow byCommand(@Param("command") Command command);
    boolean authorized(@Param("actor") UUID actor, @Param("space") UUID space);
    boolean currentConfiguration(@Param("row") McpProbeRow row);
    ConnectorCiphertext credential(@Param("row") McpProbeRow row);
    int inbox(@Param("publication") Publication publication);
    int failQueued(@Param("publication") Publication publication, @Param("reason") String reason);
    int claim(@Param("publication") Publication publication, @Param("owner") UUID owner, @Param("fence") long fence);
    int guard(@Param("command") Command command);
    int block(@Param("command") Command command, @Param("state") String state, @Param("reason") String reason);
    Integer lockCapability(@Param("row") McpProbeRow row);
    long nextVersion(@Param("row") McpProbeRow row);
    int insertVersion(@Param("row") McpProbeRow row, @Param("version") UUID version, @Param("number") long number, @Param("hash") String hash);
    int insertMcpVersion(@Param("row") McpProbeRow row, @Param("version") UUID version, @Param("count") int count, @Param("bytes") long bytes);
    int insertTool(@Param("row") McpProbeRow row, @Param("version") UUID version, @Param("tool") Tool tool);
    int insertStatus(@Param("row") McpProbeRow row, @Param("version") UUID version);
    int finish(@Param("command") Command command, @Param("version") UUID version);
    int audit(@Param("command") Command command, @Param("id") UUID id);
    int quarantine(@Param("id") UUID id, @Param("hash") String hash);
}
