package com.agentplatform.infrastructure.adapter.repository.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.SkillVersionRepository;
import com.agentplatform.infrastructure.dao.capability.SkillVersionMapper;
import java.util.UUID;

public final class MyBatisSkillVersionRepository implements SkillVersionRepository {
    private final SkillVersionMapper mapper;
    public MyBatisSkillVersionRepository(SkillVersionMapper mapper) { this.mapper = mapper; }
    @Override public boolean authorized(Actor actor) { return mapper.authorized(actor); }
    @Override public void lock(Actor actor) { mapper.lock(actor); }
    @Override public View find(Actor actor, UUID capability, UUID version, boolean lock) { return mapper.find(actor, capability, version, lock); }
    @Override public Command accepted(Actor actor, UUID request) { return mapper.accepted(actor, request); }
    @Override public boolean transition(Actor actor, UUID capability, UUID version, long expected, String from, String to) {
        return mapper.transition(actor, capability, version, expected, from, to) == 1;
    }
    @Override public void record(Actor actor, Command command, UUID trace) {
        mapper.command(actor, command); mapper.audit(actor, UUID.randomUUID(), command, trace);
    }
}
