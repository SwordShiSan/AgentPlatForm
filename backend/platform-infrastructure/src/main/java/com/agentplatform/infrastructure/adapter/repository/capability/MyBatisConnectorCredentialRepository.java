package com.agentplatform.infrastructure.adapter.repository.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.ConnectorCredentialRepository;
import com.agentplatform.domain.capability.ConnectorCiphertext;
import com.agentplatform.infrastructure.dao.capability.ConnectorCredentialMapper;
import java.util.List;
import java.util.UUID;

public final class MyBatisConnectorCredentialRepository implements ConnectorCredentialRepository {
    private final ConnectorCredentialMapper mapper;
    public MyBatisConnectorCredentialRepository(ConnectorCredentialMapper mapper) { this.mapper = mapper; }
    @Override public boolean authorized(Actor actor) { return mapper.authorized(actor); }
    @Override public void lock(Actor actor) { mapper.lock(actor); }
    @Override public Creation findRequest(Actor actor, UUID request) {
        var row = mapper.findRequest(actor, request);
        return row == null ? null : new Creation(row.fingerprint(), new Metadata(row.id(), row.revision(), row.state(), row.createdAt()));
    }
    @Override public Metadata find(Actor actor, UUID id, boolean lock) { return mapper.find(actor, id, lock); }
    @Override public List<Metadata> list(Actor actor, UUID after, int limit) { return mapper.list(actor, after, limit); }
    @Override public void insert(Actor actor, UUID request, String fingerprint, ConnectorCiphertext encrypted) { mapper.insert(actor, request, fingerprint, encrypted); }
    @Override public Revocation findRevocation(Actor actor, UUID request) { return mapper.revocation(actor, request); }
    @Override public boolean revoke(Actor actor, UUID id, long expected) { return mapper.revoke(actor, id, expected) == 1; }
    @Override public void recordRevocation(Actor actor, UUID request, UUID id, long expected, UUID trace) {
        mapper.recordRevocation(actor, request, id, expected); mapper.audit(actor, UUID.randomUUID(), id, trace);
    }
    @Override public void auditCreation(Actor actor, UUID id, UUID trace) { mapper.audit(actor, UUID.randomUUID(), id, trace); }
}
