package com.agentplatform.domain.capability;

import java.util.List;
import java.util.UUID;

/** Fenced probe state; remote I/O happens only outside the associated database transactions. */
public interface McpProbeRepository {
    record Publication(UUID id, UUID spaceId, UUID probeId, long generation, UUID relayOwner) {}
    record Command(UUID spaceId, UUID actorId, UUID probeId, UUID connectorId, long configurationRevision,
                   UUID ownerId, long generation, long fence, String endpoint, String authKind, String apiKeyHeader,
                   UUID credentialId, Long credentialRevision, String policyHash, UUID traceId) {}
    record Tool(String name, String description, String schemaJson, String schemaSha256) {
        @Override public String toString() { return "Tool[name=" + name + ", schemaSha256=" + schemaSha256 + "]"; }
    }
    Publication nextPublication(UUID relayOwner);
    void published(Publication publication);
    Command claim(Publication publication, UUID ownerId);
    ConnectorCiphertext guardAndCredential(Command command);
    void ready(Command command, List<Tool> tools);
    void failed(Command command, String code);
    void unknown(Command command, String code);
    void quarantine(UUID id, String bodyHash);
}
