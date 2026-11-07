package com.agentplatform.infrastructure.adapter.repository.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.McpVersionRepository;
import com.agentplatform.infrastructure.dao.capability.McpVersionMapper;
import java.util.List;
import java.util.UUID;

public final class MyBatisMcpVersionRepository implements McpVersionRepository {
    private final McpVersionMapper mapper;
    public MyBatisMcpVersionRepository(McpVersionMapper mapper) { this.mapper = mapper; }
    @Override public boolean authorized(Actor actor) { return mapper.authorized(actor); }
    @Override public void lock(Actor actor) { mapper.lock(actor); }
    @Override public boolean connectorExists(Actor actor, UUID id) { return mapper.exists(actor, id); }
    @Override public List<Version> list(Actor actor, UUID id, Long before, int limit) { return mapper.list(actor, id, before, limit); }
    @Override public Version find(Actor actor, UUID id, UUID version, boolean lock) { return mapper.find(actor, id, version, lock); }
    @Override public List<Tool> tools(Actor actor, UUID id, UUID version) { return mapper.tools(actor, id, version); }
    @Override public boolean eligible(Actor actor, Version version, String policy) { return mapper.eligible(actor, version, policy); }
    @Override public Activation activation(Actor actor, UUID id) { return mapper.activation(actor, id); }
    @Override public void initialize(Actor actor, UUID id) { mapper.initialize(actor, id); }
    @Override public boolean publish(Actor actor, Version version) { return mapper.publish(actor, version) == 1; }
    @Override public boolean setActivation(Actor actor, UUID id, long expected, UUID version) { return mapper.setActivation(actor, id, expected, version) == 1; }
    @Override public Command accepted(Actor actor, UUID request) { return mapper.accepted(actor, request); }
    @Override public void record(Actor actor, UUID request, Command command, UUID trace) {
        mapper.record(actor, request, command); mapper.audit(actor, UUID.randomUUID(), command, trace);
    }
}
