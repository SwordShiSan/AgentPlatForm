package com.agentplatform.infrastructure.adapter.repository.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository;
import com.agentplatform.domain.capability.CapabilityBindings.Selection;
import com.agentplatform.infrastructure.dao.capability.CapabilityBindingMapper;
import java.util.List;
import java.util.UUID;

/** Uses a transaction-bound MyBatis session; no network work takes place here. */
public final class MyBatisCapabilityBindingRepository implements CapabilityBindingRepository {
    private final CapabilityBindingMapper mapper;
    public MyBatisCapabilityBindingRepository(CapabilityBindingMapper mapper) { this.mapper = mapper; }
    @Override public boolean authorized(Actor actor, UUID workspaceId) { return mapper.authorized(actor, workspaceId); }
    @Override public void lock(Actor actor, UUID workspaceId) { mapper.lock(actor, workspaceId); }
    @Override public BindingSet find(Actor actor, UUID workspaceId) {
        var row = mapper.find(actor, workspaceId);
        return row == null ? null : new BindingSet(row.id(), row.revision(), row.inheritPersonal(), mapper.selections(actor, row.id()));
    }
    @Override public void initialize(Actor actor, UUID workspaceId, UUID id) { mapper.initialize(actor, workspaceId, id); }
    @Override public boolean replace(Actor actor, UUID setId, long expected, boolean inherit, List<Selection> selections) {
        if (mapper.update(actor, setId, expected, inherit) != 1) return false;
        mapper.clear(actor, setId);
        for (Selection selection : selections) mapper.add(actor, setId, selection);
        return true;
    }
    @Override public boolean published(Actor actor, Selection selection) { return mapper.published(actor, selection); }
    @Override public AcceptedRequest accepted(Actor actor, UUID requestId) {
        var row = mapper.accepted(actor, requestId);
        return row == null ? null : new AcceptedRequest(row.requestHash(), new BindingSet(row.setId(), row.revision(),
                row.inheritPersonal(), mapper.requestSelections(actor, requestId)));
    }
    @Override public void recordAccepted(Actor actor, UUID requestId, String requestHash, BindingSet result, UUID traceId) {
        mapper.recordRequest(actor, requestId, requestHash, result.id(), result.revision(), result.inheritPersonal());
        for (Selection selection : result.selections()) mapper.recordSelection(actor, requestId, selection);
        mapper.audit(actor, UUID.randomUUID(), result.id(), traceId);
    }
}
