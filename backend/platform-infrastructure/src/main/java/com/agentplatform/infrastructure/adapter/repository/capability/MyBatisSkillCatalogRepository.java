package com.agentplatform.infrastructure.adapter.repository.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.SkillCatalogRepository;
import com.agentplatform.infrastructure.dao.capability.SkillCatalogMapper;
import java.util.List;
import java.util.UUID;

public final class MyBatisSkillCatalogRepository implements SkillCatalogRepository {
    private final SkillCatalogMapper mapper;
    public MyBatisSkillCatalogRepository(SkillCatalogMapper mapper) { this.mapper = mapper; }
    @Override public boolean authorized(Actor actor) { return mapper.authorized(actor); }
    @Override public boolean exists(Actor actor, UUID capability) { return mapper.exists(actor, capability); }
    @Override public List<Item> skills(Actor actor, UUID after, int limit) { return mapper.skills(actor, after, limit); }
    @Override public List<Item> versions(Actor actor, UUID capability, Long before, int limit) { return mapper.versions(actor, capability, before, limit); }
}
