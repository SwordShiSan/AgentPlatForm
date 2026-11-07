package com.agentplatform.app.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.McpVersionRepository;
import com.agentplatform.domain.capability.McpVersionRepository.*;
import com.agentplatform.domain.files.FileFailure;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit enablement never changes the immutable discovered version or another binding. */
public final class McpVersionService {
    public record Page(List<Version> items, Long nextCursor) { public Page { items = List.copyOf(items); } }
    public record Detail(Version version, List<Tool> tools, Activation activation, boolean eligible) {
        public Detail { tools = List.copyOf(tools); }
    }
    private final McpVersionRepository repository;
    private final TransactionTemplate transactions;
    private final String policyHash;
    public McpVersionService(McpVersionRepository repository, TransactionTemplate transactions, String policyHash) {
        this.repository = Objects.requireNonNull(repository); this.transactions = Objects.requireNonNull(transactions);
        if (policyHash == null || !policyHash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("MCP_POLICY_INVALID");
        this.policyHash = policyHash;
    }
    public Page list(Actor actor, UUID connector, Long before, int limit) {
        if (limit < 1 || limit > 100 || before != null && before < 1) throw new FileFailure(400, "INVALID_PAGE_SIZE");
        return transactions.execute(status -> {
            access(actor); exists(actor, connector); var rows = repository.list(actor, connector, before, limit + 1);
            var page = rows.subList(0, Math.min(limit, rows.size()));
            return new Page(page, rows.size() > limit ? page.getLast().versionNumber() : null);
        });
    }
    public Detail get(Actor actor, UUID connector, UUID version) {
        return transactions.execute(status -> {
            access(actor); var value = required(actor, connector, version, false);
            return new Detail(value, repository.tools(actor, connector, version), activation(actor, connector), repository.eligible(actor, value, policyHash));
        });
    }
    public Activation current(Actor actor, UUID connector) {
        return transactions.execute(status -> { access(actor); exists(actor, connector); return activation(actor, connector); });
    }
    private Activation activation(Actor actor, UUID connector) {
        var value = repository.activation(actor, connector); return value == null ? new Activation(0, null) : value;
    }
    public Activation change(Actor actor, UUID connector, UUID version, long expected, UUID request, UUID trace) {
        if (actor == null || connector == null || expected < 0 || request == null || trace == null)
            throw new FileFailure(400, "MCP_ACTIVATION_INVALID");
        String action = version == null ? "DISABLE" : "ENABLE";
        return transactions.execute(status -> {
            repository.lock(actor); access(actor); exists(actor, connector);
            var previous = repository.accepted(actor, request);
            if (previous != null) {
                if (!previous.connectorId().equals(connector) || !previous.action().equals(action)
                        || !Objects.equals(previous.versionId(), version) || previous.expectedRevision() != expected)
                    throw new FileFailure(409, "IDEMPOTENCY_CONFLICT");
                return new Activation(previous.resultRevision(), previous.resultVersionId());
            }
            repository.initialize(actor, connector);
            if (activation(actor, connector).revision() != expected) throw new FileFailure(412, "MCP_ACTIVATION_VERSION_CONFLICT");
            if (version != null) {
                Version selected = required(actor, connector, version, true);
                if (!("DRAFT".equals(selected.state()) || "PUBLISHED".equals(selected.state())))
                    throw new FileFailure(409, "MCP_VERSION_UNAVAILABLE");
                if (!repository.eligible(actor, selected, policyHash)) throw new FileFailure(422, "MCP_VERSION_STALE");
                if ("DRAFT".equals(selected.state()) && !repository.publish(actor, selected)) throw new FileFailure(412, "MCP_VERSION_CONFLICT");
            }
            if (!repository.setActivation(actor, connector, expected, version)) throw new FileFailure(412, "MCP_ACTIVATION_VERSION_CONFLICT");
            repository.record(actor, request, new Command(connector, action, version, expected, expected + 1, version), trace);
            return new Activation(expected + 1, version);
        });
    }
    private void access(Actor actor) { if (actor == null || !repository.authorized(actor)) throw new FileFailure(404, "RESOURCE_NOT_FOUND"); }
    private void exists(Actor actor, UUID connector) { if (connector == null || !repository.connectorExists(actor, connector)) throw new FileFailure(404, "RESOURCE_NOT_FOUND"); }
    private Version required(Actor actor, UUID connector, UUID version, boolean lock) {
        var value = repository.find(actor, connector, version, lock); if (value == null) throw new FileFailure(404, "RESOURCE_NOT_FOUND"); return value;
    }
}
