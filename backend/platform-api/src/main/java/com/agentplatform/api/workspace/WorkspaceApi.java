package com.agentplatform.api.workspace;

import java.util.List;

public interface WorkspaceApi {
    record Caller(String userId, String spaceId) {}
    record View(String id, String spaceId, String name, String state, String environmentState,
            long quotaBytes, long quotaInodes, long version, String operationId, String errorCode,
            String createdAt, boolean fileAccess, boolean shellAccess) {}
    record Page(List<View> items, String nextCursor) {}
    View create(Caller actor, String requestId, String name);
    View get(Caller actor, String id);
    Page list(Caller actor, String cursor, int limit);
    View retry(Caller actor, String id, String requestId, long expectedVersion);
}
