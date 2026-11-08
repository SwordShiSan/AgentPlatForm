package com.agentplatform.infrastructure.dao.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.SkillCatalogRepository.Item;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SkillCatalogMapper {
    boolean authorized(@Param("actor") Actor actor);
    boolean exists(@Param("actor") Actor actor, @Param("capability") UUID capability);
    List<Item> skills(@Param("actor") Actor actor, @Param("after") UUID after, @Param("limit") int limit);
    List<Item> versions(@Param("actor") Actor actor, @Param("capability") UUID capability,
                        @Param("before") Long before, @Param("limit") int limit);
}
