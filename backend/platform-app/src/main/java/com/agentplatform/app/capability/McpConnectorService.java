package com.agentplatform.app.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.McpConnectorRepository;
import com.agentplatform.domain.capability.McpConnectorRepository.Configuration;
import com.agentplatform.domain.capability.McpConnectorRepository.Probe;
import com.agentplatform.domain.capability.McpEndpointPolicy;
import com.agentplatform.domain.files.FileFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;

/** Resolves connector settings and atomically queues discovery; discovery runs only after durable MQ handoff. */
public final class McpConnectorService {
    public record Input(String endpoint, String authKind, String apiKeyHeader, UUID credentialId, Long credentialRevision) {}
    public record Page(List<Configuration> items, UUID nextCursor) { public Page { items = List.copyOf(items); } }
    private final McpConnectorRepository repository;
    private final TransactionTemplate transactions;
    private final String policyHash;
    public McpConnectorService(McpConnectorRepository repository, TransactionTemplate transactions, String policyHash) {
        this.repository = Objects.requireNonNull(repository); this.transactions = Objects.requireNonNull(transactions);
        if (policyHash == null || !policyHash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("MCP_POLICY_INVALID");
        this.policyHash = policyHash;
    }
    public Configuration create(Actor actor, UUID request, Input input, UUID trace) { return change(actor, null, 0, request, input, trace); }
    public Configuration update(Actor actor, UUID id, long expected, UUID request, Input input, UUID trace) {
        if (id == null || expected < 1) throw new FileFailure(400, "MCP_CONFIGURATION_INVALID");
        return change(actor, id, expected, request, input, trace);
    }
    private Configuration change(Actor actor, UUID id, long expected, UUID request, Input input, UUID trace) {
        if (actor == null || request == null || trace == null) throw new FileFailure(400, "MCP_CONFIGURATION_INVALID");
        Input checked = validate(input);
        String requestHash = hash("mcp-config-v1\n" + (id == null ? "new" : id) + "\n" + expected + "\n" + canonical(checked));
        return transactions.execute(status -> {
            repository.lock(actor); access(actor);
            var prior = repository.accepted(actor, request);
            if (prior != null) {
                if (!prior.hash().equals(requestHash)) throw new FileFailure(409, "IDEMPOTENCY_CONFLICT");
                return prior.snapshot();
            }
            requireCredential(actor, checked);
            UUID connector = id == null ? UUID.randomUUID() : id;
            if (id != null) {
                var current = required(actor, id, true);
                if (current.revision() != expected) throw new FileFailure(412, "MCP_CONFIGURATION_VERSION_CONFLICT");
            }
            var next = new Configuration(connector, checked.endpoint(), checked.authKind(), checked.apiKeyHeader(),
                    checked.credentialId(), checked.credentialRevision(), expected + 1);
            if (id == null) repository.insert(actor, next);
            else if (!repository.update(actor, next, expected)) throw new FileFailure(412, "MCP_CONFIGURATION_VERSION_CONFLICT");
            repository.record(actor, request, requestHash, next, id == null ? "CONNECTOR_CREATED" : "CONNECTOR_UPDATED", trace);
            return next;
        });
    }
    public Configuration get(Actor actor, UUID id) {
        return transactions.execute(status -> { access(actor); return required(actor, id, false); });
    }
    public Configuration byRequest(Actor actor, UUID request) {
        return transactions.execute(status -> { access(actor); var row = repository.accepted(actor, request);
            if (row == null) throw new FileFailure(404, "RESOURCE_NOT_FOUND"); return row.snapshot(); });
    }
    public Page list(Actor actor, UUID after, int limit) {
        if (limit < 1 || limit > 100) throw new FileFailure(400, "INVALID_PAGE_SIZE");
        return transactions.execute(status -> {
            access(actor); var rows = repository.list(actor, after, limit + 1); var page = rows.subList(0, Math.min(limit, rows.size()));
            return new Page(page, rows.size() > limit ? page.getLast().id() : null);
        });
    }
    public Probe enqueue(Actor actor, UUID connector, long expected, UUID request, UUID trace) {
        if (actor == null || connector == null || expected < 1 || request == null || trace == null) throw new FileFailure(400, "MCP_PROBE_INVALID");
        String requestHash = hash("mcp-probe-v1\n" + connector + "\n" + expected + "\n" + policyHash);
        return transactions.execute(status -> {
            repository.lock(actor); access(actor);
            Probe prior = repository.probeRequest(actor, request, requestHash);
            if (prior != null) return prior;
            var configuration = required(actor, connector, true);
            if (configuration.revision() != expected) throw new FileFailure(412, "MCP_CONFIGURATION_VERSION_CONFLICT");
            requireCredential(actor, new Input(configuration.endpoint(), configuration.authKind(), configuration.apiKeyHeader(),
                    configuration.credentialId(), configuration.credentialRevision()));
            UUID id = UUID.randomUUID();
            repository.enqueueProbe(actor, id, request, requestHash, configuration, policyHash, UUID.randomUUID(), trace);
            return repository.probe(actor, id);
        });
    }
    public Probe probe(Actor actor, UUID id) {
        return transactions.execute(status -> { access(actor); var row = repository.probe(actor, id);
            if (row == null) throw new FileFailure(404, "RESOURCE_NOT_FOUND"); return row; });
    }
    public Probe probeByRequest(Actor actor, UUID request) {
        return transactions.execute(status -> { access(actor); var row = repository.probeByRequest(actor, request);
            if (row == null) throw new FileFailure(404, "RESOURCE_NOT_FOUND"); return row; });
    }
    private void access(Actor actor) { if (actor == null || !repository.authorized(actor)) throw new FileFailure(404, "RESOURCE_NOT_FOUND"); }
    private Configuration required(Actor actor, UUID id, boolean lock) {
        var config = repository.find(actor, id, lock); if (config == null) throw new FileFailure(404, "RESOURCE_NOT_FOUND"); return config;
    }
    private void requireCredential(Actor actor, Input input) {
        if (input.credentialId() != null && !repository.activeCredential(actor, input.credentialId(), input.credentialRevision()))
            throw new FileFailure(422, "CONNECTOR_CREDENTIAL_UNAVAILABLE");
    }
    private static Input validate(Input input) {
        if (input == null || input.authKind() == null) throw new FileFailure(400, "MCP_CONFIGURATION_INVALID");
        String endpoint;
        try { endpoint = McpEndpointPolicy.external(input.endpoint()).toASCIIString(); }
        catch (IllegalArgumentException invalid) { throw new FileFailure(422, "MCP_ENDPOINT_REJECTED"); }
        boolean none = "NONE".equals(input.authKind()) && input.apiKeyHeader() == null && input.credentialId() == null && input.credentialRevision() == null;
        boolean bearer = "BEARER".equals(input.authKind()) && input.apiKeyHeader() == null && input.credentialId() != null && input.credentialRevision() != null && input.credentialRevision() > 0;
        boolean apiKey = "API_KEY".equals(input.authKind()) && ("X-API-Key".equals(input.apiKeyHeader()) || "api-key".equals(input.apiKeyHeader()))
                && input.credentialId() != null && input.credentialRevision() != null && input.credentialRevision() > 0;
        if (!(none || bearer || apiKey)) throw new FileFailure(422, "MCP_AUTHENTICATION_INVALID");
        return new Input(endpoint, input.authKind(), input.apiKeyHeader(), input.credentialId(), input.credentialRevision());
    }
    private static String canonical(Input input) {
        return input.endpoint() + "\n" + input.authKind() + "\n" + input.apiKeyHeader() + "\n" + input.credentialId() + "\n" + input.credentialRevision();
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
