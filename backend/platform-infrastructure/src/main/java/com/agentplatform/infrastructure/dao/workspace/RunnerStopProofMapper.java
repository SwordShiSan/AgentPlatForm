package com.agentplatform.infrastructure.dao.workspace;

import com.agentplatform.domain.workspace.*;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RunnerStopProofMapper {
    RunnerInstance lockRunner(@Param("id") UUID id);
    String evidence(@Param("id") UUID id);
    boolean fresh(@Param("proof") StoppedRunner proof);
    void insert(@Param("proof") StoppedRunner proof);
}
