package com.agentplatform.infrastructure.dao.capability;

import com.agentplatform.domain.capability.CapabilityMaterializationCommand;
import com.agentplatform.domain.capability.CapabilityMaterializationRepository.Record;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CapabilityMaterializationMapper {
    boolean currentLease(@Param("command") CapabilityMaterializationCommand command);
    int insert(@Param("command") CapabilityMaterializationCommand command,@Param("hash") String hash,@Param("json") String json);
    Record find(@Param("operation") UUID operation);
    int committed(@Param("command") CapabilityMaterializationCommand command,@Param("json") String json,@Param("hash") String hash);
    int failed(@Param("command") CapabilityMaterializationCommand command,@Param("code") String code,
            @Param("json") String json,@Param("hash") String hash);
    int blocked(@Param("command") CapabilityMaterializationCommand command,@Param("code") String code,
            @Param("json") String json,@Param("hash") String hash);
}
