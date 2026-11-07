package com.agentplatform.infrastructure.dao.po;

import java.util.UUID;

public record SkillImportRow(UUID id, UUID targetCapabilityId, UUID targetVersionId, UUID targetAssetId,
                             String state, long declaredBytes, String declaredSha256,
                             String stagingObjectKey, long revision, String requestHash) {}
