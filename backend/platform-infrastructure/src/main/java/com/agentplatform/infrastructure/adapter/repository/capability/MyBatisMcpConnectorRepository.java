package com.agentplatform.infrastructure.adapter.repository.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.McpConnectorRepository;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.infrastructure.dao.capability.McpConnectorMapper;
import java.util.UUID;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

public final class MyBatisMcpConnectorRepository implements McpConnectorRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final McpConnectorMapper mapper;
    public MyBatisMcpConnectorRepository(McpConnectorMapper mapper) { this.mapper = mapper; }
    @Override public boolean authorized(Actor actor) { return mapper.authorized(actor); }
    @Override public void lock(Actor actor) { mapper.lock(actor); }
    @Override public boolean activeCredential(Actor actor, UUID id, long revision) { return mapper.activeCredential(actor, id, revision); }
    @Override public Configuration find(Actor actor, UUID id, boolean lock) { return mapper.find(actor, id, lock); }
    @Override public List<Configuration> list(Actor actor, UUID after, int limit) { return mapper.list(actor, after, limit); }
    @Override public Accepted accepted(Actor actor, UUID request) {
        var row = mapper.accepted(actor, request);
        return row == null ? null : new Accepted(row.requestHash(), JSON.readValue(row.resultSnapshot(), Configuration.class));
    }
    @Override public void insert(Actor actor, Configuration config) { mapper.insertCapability(actor, config); mapper.insert(actor, config); }
    @Override public boolean update(Actor actor, Configuration config, long expected) { return mapper.update(actor, config, expected) == 1; }
    @Override public void record(Actor actor, UUID request, String hash, Configuration config, String action, UUID trace) {
        mapper.record(actor, request, hash, config, JSON.writeValueAsString(config)); mapper.audit(actor, UUID.randomUUID(), action, config.id(), trace);
    }
    @Override public Probe probeRequest(Actor actor, UUID request, String requestHash) {
        var probe = mapper.probeRequest(actor, request);
        if (probe != null && !requestHash.equals(mapper.probeRequestHash(actor, request))) throw new FileFailure(409, "IDEMPOTENCY_CONFLICT");
        return probe;
    }
    @Override public Probe probe(Actor actor, UUID id) { return mapper.probe(actor, id); }
    @Override public Probe probeByRequest(Actor actor, UUID request) { return mapper.probeRequest(actor, request); }
    @Override public void enqueueProbe(Actor actor, UUID id, UUID request, String hash, Configuration config, String policy, UUID event, UUID trace) {
        mapper.insertProbe(actor, id, request, hash, config, policy, trace); mapper.outbox(actor, id, event);
        mapper.audit(actor, UUID.randomUUID(), "CONNECTOR_TEST_REQUESTED", id, trace);
    }
}
