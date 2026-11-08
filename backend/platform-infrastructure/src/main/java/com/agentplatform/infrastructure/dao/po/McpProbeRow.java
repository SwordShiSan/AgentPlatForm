package com.agentplatform.infrastructure.dao.po;

import java.util.UUID;

public record McpProbeRow(UUID id, UUID spaceId, UUID ownerUserId, UUID capabilityId, long configurationRevision,
                          String endpoint, String authKind, String apiKeyHeader, UUID credentialId, Long credentialRevision,
                          String policyHash, long generation, long revision, UUID ownerId, Long fence, String state, UUID traceId) {}
