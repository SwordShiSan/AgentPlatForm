package com.agentplatform.domain.attachment;

import java.util.UUID;

/** A verified extraction of exact immutable source bytes; no document body is persisted here. */
public record AttachmentSource(UUID extractionId,String sourceKind,UUID workspaceId,UUID fileId,UUID versionId,
        UUID attachmentId,UUID sourceAssetId,String sourceSha256,String textSha256,String name,
        String textObjectKey,long textBytes) { }
