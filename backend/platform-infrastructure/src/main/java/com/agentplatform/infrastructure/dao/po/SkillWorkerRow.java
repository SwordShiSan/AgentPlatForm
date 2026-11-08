package com.agentplatform.infrastructure.dao.po;

import java.util.UUID;

public record SkillWorkerRow(UUID id, UUID spaceId, UUID ownerUserId, UUID targetCapabilityId,
                             UUID targetVersionId, UUID targetAssetId, String stagingObjectKey,
                             long declaredBytes, String declaredSha256, long generation, long revision,
                             UUID ownerId, Long fence, String state) {}
