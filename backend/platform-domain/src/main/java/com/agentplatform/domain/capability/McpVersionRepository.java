package com.agentplatform.domain.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import java.util.List;
import java.util.UUID;

public interface McpVersionRepository {
    record Version(UUID connectorId, UUID versionId, long versionNumber, String state, long revision,
                   String endpoint, String authKind, UUID credentialId, Long credentialRevision,
                   String policyHash, int toolCount, long sourceConfigurationRevision) {}
    record Tool(String name, String description, String schemaJson, String schemaSha256) {}
    record Activation(long revision, UUID enabledVersionId) {}
    record Command(UUID connectorId, String action, UUID versionId, long expectedRevision, long resultRevision, UUID resultVersionId) {}
    boolean authorized(Actor actor);
    void lock(Actor actor);
    boolean connectorExists(Actor actor, UUID connectorId);
    List<Version> list(Actor actor, UUID connectorId, Long before, int limit);
    Version find(Actor actor, UUID connectorId, UUID versionId, boolean lock);
    List<Tool> tools(Actor actor, UUID connectorId, UUID versionId);
    boolean eligible(Actor actor, Version version, String policyHash);
    Activation activation(Actor actor, UUID connectorId);
    void initialize(Actor actor, UUID connectorId);
    boolean publish(Actor actor, Version version);
    boolean setActivation(Actor actor, UUID connectorId, long expected, UUID enabledVersionId);
    Command accepted(Actor actor, UUID requestId);
    void record(Actor actor, UUID requestId, Command command, UUID trace);
}
