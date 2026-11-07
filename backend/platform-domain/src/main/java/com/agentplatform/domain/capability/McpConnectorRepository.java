package com.agentplatform.domain.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import java.util.UUID;
import java.util.List;

/** Connector settings and probe admission; all methods are short database operations. */
public interface McpConnectorRepository {
    record Configuration(UUID id, String endpoint, String authKind, String apiKeyHeader,
                         UUID credentialId, Long credentialRevision, long revision) {}
    record Accepted(String hash, Configuration snapshot) {}
    record Probe(UUID id, UUID connectorId, long configurationRevision, String state, UUID resultVersionId, long revision) {}
    boolean authorized(Actor actor);
    void lock(Actor actor);
    boolean activeCredential(Actor actor, UUID credentialId, long revision);
    Configuration find(Actor actor, UUID id, boolean lock);
    List<Configuration> list(Actor actor, UUID after, int limit);
    Accepted accepted(Actor actor, UUID requestId);
    void insert(Actor actor, Configuration configuration);
    boolean update(Actor actor, Configuration configuration, long expected);
    void record(Actor actor, UUID requestId, String hash, Configuration snapshot, String action, UUID trace);
    Probe probeRequest(Actor actor, UUID requestId, String requestHash);
    Probe probe(Actor actor, UUID id);
    void enqueueProbe(Actor actor, UUID id, UUID requestId, String requestHash, Configuration configuration, String policyHash, UUID event, UUID trace);
}
