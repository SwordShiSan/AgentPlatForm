package com.agentplatform.infrastructure.dao.po;

import java.time.Instant;

public final class ContextProjectionRows {
    private ContextProjectionRows() {}
    public record ProjectionRow(long epoch,String bundleHash,String watermarkJson,String sourceManifestSha256,
                               String summary,String summarySha256,long estimatedInputTokens,String policyVersion,Instant createdAt) {}
}
