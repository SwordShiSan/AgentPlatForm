package com.agentplatform.app.capability;

import com.agentplatform.domain.capability.ConnectorCiphertext;
import com.agentplatform.domain.capability.McpProbeRepository;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.infrastructure.dao.capability.McpProbeMapper;
import com.agentplatform.infrastructure.dao.po.McpProbeRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Owns fenced probe facts; remote discovery is always outside the transaction. */
public final class TransactionalMcpProbeRepository implements McpProbeRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final McpProbeMapper mapper;
    private final TransactionTemplate transactions;
    private final String policyHash;
    public TransactionalMcpProbeRepository(McpProbeMapper mapper, TransactionTemplate transactions, String policyHash) {
        this.mapper = Objects.requireNonNull(mapper); this.transactions = Objects.requireNonNull(transactions);
        if (policyHash == null || !policyHash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("MCP_POLICY_INVALID");
        this.policyHash = policyHash;
    }
    private <T> T tx(Supplier<T> operation) { return transactions.execute(status -> operation.get()); }
    private static void one(int count) { if (count != 1) throw new FileFailure(409, "MCP_PROBE_STALE_OWNER"); }
    @Override public Publication nextPublication(UUID owner) { return tx(() -> mapper.next(owner)); }
    @Override public void published(Publication publication) { tx(() -> { one(mapper.published(publication)); return null; }); }
    @Override public Command claim(Publication publication, UUID owner) {
        return tx(() -> {
            var row = mapper.byPublication(publication);
            if (row == null) throw new FileFailure(409, "MCP_PROBE_DELIVERY_UNKNOWN");
            if (mapper.inbox(publication) == 0) return null;
            String reason = eligibility(row);
            if (reason != null) { one(mapper.failQueued(publication, reason)); return null; }
            long fence = row.revision() + 1; one(mapper.claim(publication, owner, fence));
            return command(row, owner, fence);
        });
    }
    @Override public ConnectorCiphertext guardAndCredential(Command command) {
        return tx(() -> {
            var row = guarded(command);
            ConnectorCiphertext credential = "NONE".equals(row.authKind()) ? null : mapper.credential(row);
            if (!"NONE".equals(row.authKind()) && credential == null) throw new FileFailure(403, "CONNECTOR_CREDENTIAL_UNAVAILABLE");
            one(mapper.guard(command)); return credential;
        });
    }
    @Override public void ready(Command command, List<Tool> input) {
        List<Tool> tools = validate(input);
        tx(() -> {
            var row = guarded(command);
            if (!"NONE".equals(row.authKind()) && mapper.credential(row) == null) throw new FileFailure(403, "CONNECTOR_CREDENTIAL_UNAVAILABLE");
            if (mapper.lockCapability(row) == null) throw new FileFailure(409, "MCP_CONNECTOR_MISMATCH");
            UUID version = UUID.randomUUID(); long number = mapper.nextVersion(row);
            long bytes = tools.stream().mapToLong(t -> t.schemaJson().getBytes(StandardCharsets.UTF_8).length).sum();
            one(mapper.insertVersion(row, version, number, versionHash(row, tools)));
            one(mapper.insertMcpVersion(row, version, tools.size(), bytes));
            for (Tool tool : tools) one(mapper.insertTool(row, version, tool));
            one(mapper.insertStatus(row, version)); one(mapper.finish(command, version)); one(mapper.audit(command, UUID.randomUUID()));
            return null;
        });
    }
    @Override public void failed(Command command, String code) { finishFailure(command, "FAILED", code); }
    @Override public void unknown(Command command, String code) { finishFailure(command, "BLOCKED", code); }
    @Override public void quarantine(UUID id, String hash) { tx(() -> { one(mapper.quarantine(id, hash)); return null; }); }
    private void finishFailure(Command command, String state, String code) {
        if (code == null || !code.matches("[A-Z0-9_]{1,64}")) throw new IllegalArgumentException("MCP_ERROR_INVALID");
        tx(() -> { if (mapper.byCommand(command) == null) throw new FileFailure(409, "MCP_PROBE_STALE_OWNER");
            one(mapper.block(command, state, code)); return null; });
    }
    private McpProbeRow guarded(Command command) {
        var row = mapper.byCommand(command);
        if (row == null || !matches(command, row)) throw new FileFailure(409, "MCP_PROBE_STALE_OWNER");
        String reason = eligibility(row);
        if (reason != null) throw new FileFailure(403, reason);
        return row;
    }
    private String eligibility(McpProbeRow row) {
        if (!policyHash.equals(row.policyHash())) return "MCP_POLICY_CHANGED";
        if (!mapper.authorized(row.ownerUserId(), row.spaceId())) return "MCP_AUTHORIZATION_REVOKED";
        if (!mapper.currentConfiguration(row)) return "MCP_CONFIGURATION_CHANGED";
        if (!"NONE".equals(row.authKind()) && mapper.credential(row) == null) return "CONNECTOR_CREDENTIAL_UNAVAILABLE";
        return null;
    }
    private static Command command(McpProbeRow r, UUID owner, long fence) {
        return new Command(r.spaceId(), r.ownerUserId(), r.id(), r.capabilityId(), r.configurationRevision(), owner,
                r.generation(), fence, r.endpoint(), r.authKind(), r.apiKeyHeader(), r.credentialId(), r.credentialRevision(), r.policyHash(), r.traceId());
    }
    private static boolean matches(Command c, McpProbeRow r) {
        return c.endpoint().equals(r.endpoint()) && c.authKind().equals(r.authKind()) && Objects.equals(c.apiKeyHeader(), r.apiKeyHeader())
                && Objects.equals(c.credentialId(), r.credentialId()) && Objects.equals(c.credentialRevision(), r.credentialRevision())
                && c.traceId().equals(r.traceId());
    }
    private static List<Tool> validate(List<Tool> source) {
        if (source == null || source.size() > 100) throw new FileFailure(422, "MCP_TOOL_LIMIT");
        var names = new HashSet<String>(); var sorted = new ArrayList<Tool>(); long total = 0;
        for (Tool tool : source) {
            if (tool == null || tool.name() == null || tool.name().isBlank() || tool.name().getBytes(StandardCharsets.UTF_8).length > 512
                    || !names.add(tool.name()) || tool.schemaJson() == null || tool.schemaSha256() == null
                    || tool.description() != null && tool.description().getBytes(StandardCharsets.UTF_8).length > 65536)
                throw new FileFailure(422, "MCP_TOOL_INVALID");
            byte[] bytes = tool.schemaJson().getBytes(StandardCharsets.UTF_8); total += bytes.length;
            if (bytes.length > 65536 || total > 1048576 || !hash(bytes).equals(tool.schemaSha256()) || !JSON.readTree(tool.schemaJson()).isObject())
                throw new FileFailure(422, "MCP_SCHEMA_INVALID");
            sorted.add(tool);
        }
        sorted.sort(Comparator.comparing(Tool::name)); return List.copyOf(sorted);
    }
    private static String versionHash(McpProbeRow row, List<Tool> tools) {
        String canonical = JSON.writeValueAsString(List.of("mcp-version-v1", row.endpoint(), row.authKind(),
                Objects.toString(row.apiKeyHeader(), ""), Objects.toString(row.credentialId(), ""),
                Objects.toString(row.credentialRevision(), ""), row.policyHash(), tools));
        return hash(canonical.getBytes(StandardCharsets.UTF_8));
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
