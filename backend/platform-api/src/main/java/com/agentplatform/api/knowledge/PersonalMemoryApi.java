package com.agentplatform.api.knowledge;

import java.time.Instant;
import java.util.List;

public interface PersonalMemoryApi {
    record Caller(String userId, String spaceId) {}
    record Candidate(String scopeKind, String workspaceId, String category, String topicKey, String content) {}
    record Change(String action, String category, String topicKey, String content, long expectedRevision) {}
    record View(String id, String scopeKind, String workspaceId, String category, String topicKey,
                String content, String status, long revision, Instant expiresAt, Instant confirmedAt) {}
    View candidate(Caller caller, String requestId, Candidate body);
    List<View> list(Caller caller, String status, String workspaceId);
    View change(Caller caller, String id, String requestId, Change body);
}
