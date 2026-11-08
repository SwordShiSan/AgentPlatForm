package com.agentplatform.domain.knowledge;

import com.agentplatform.domain.knowledge.KnowledgeData.*;
import java.util.List;
import java.util.UUID;

/** Personal memory is private to its owner; confirmation is the only path to ACTIVE. */
public interface MemoryRepository {
    Memory candidate(Actor actor, String scopeKind, UUID workspaceId, String category, String topic,
                     String content, UUID requestId, String requestHash);
    List<Memory> list(Actor actor, String status, UUID workspaceId);
    Memory change(Actor actor, UUID id, long expectedRevision, String action, String category,
                  String topic, String content, UUID requestId, String requestHash);
    List<Memory> recall(Actor actor, UUID workspaceId, int limit);
}
