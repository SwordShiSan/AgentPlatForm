package com.agentplatform.types;

import java.util.UUID;

/** Public typed immutable source reference; never a filesystem permission. */
public record AttachmentReference(String kind,String fileId,String versionId,String attachmentId,String extractionId) {
    public AttachmentReference {
        UUID.fromString(extractionId);
        if("FILE_VERSION".equals(kind)&&fileId!=null&&versionId!=null&&attachmentId==null){UUID.fromString(fileId);UUID.fromString(versionId);}
        else if("CHAT_ATTACHMENT".equals(kind)&&fileId==null&&versionId==null&&attachmentId!=null)UUID.fromString(attachmentId);
        else throw new IllegalArgumentException("Invalid attachment source");
    }
}
