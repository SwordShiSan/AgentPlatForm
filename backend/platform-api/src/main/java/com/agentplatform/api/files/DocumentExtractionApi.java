package com.agentplatform.api.files;

import java.util.List;

public interface DocumentExtractionApi {
    record Caller(String userId,String spaceId) {}
    record Create(String fileId,String versionId,String attachmentId) {}
    record View(String id,String sourceKind,String fileId,String versionId,String attachmentId,String name,
            String status,Long bytes,String sha256,String errorCode,boolean retryable,long revision,List<String> allowedActions) {}
    View create(Caller caller,String request,Create body);
    View get(Caller caller,String id);
}
