package com.agentplatform.infrastructure.dao.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.CapabilityBindings.Selection;
import com.agentplatform.infrastructure.dao.po.CapabilityBindingSetRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CapabilityBindingMapper {
    boolean authorized(@Param("actor") Actor actor, @Param("workspaceId") UUID workspaceId);
    void lock(@Param("actor") Actor actor, @Param("workspaceId") UUID workspaceId);
    CapabilityBindingSetRow find(@Param("actor") Actor actor, @Param("workspaceId") UUID workspaceId);
    List<Selection> selections(@Param("actor") Actor actor, @Param("setId") UUID setId);
    int initialize(@Param("actor") Actor actor, @Param("workspaceId") UUID workspaceId, @Param("id") UUID id);
    int update(@Param("actor") Actor actor, @Param("setId") UUID setId,
               @Param("revision") long revision, @Param("inherit") boolean inherit);
    int clear(@Param("actor") Actor actor, @Param("setId") UUID setId);
    int add(@Param("actor") Actor actor, @Param("setId") UUID setId, @Param("selection") Selection selection);
    boolean published(@Param("actor") Actor actor, @Param("selection") Selection selection);
}
