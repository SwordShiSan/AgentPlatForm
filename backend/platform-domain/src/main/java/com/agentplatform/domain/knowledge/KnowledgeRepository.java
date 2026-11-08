package com.agentplatform.domain.knowledge;

import com.agentplatform.domain.knowledge.KnowledgeData.*;
import java.util.List;
import java.util.UUID;

/** Short MyBatis transactions. Asset reads and vector inference occur outside transactions. */
public interface KnowledgeRepository {
    Base createBase(Actor actor, String name, UUID requestId, String requestHash);
    List<Base> bases(Actor actor);
    Base base(Actor actor, UUID id);
    Document addDocument(Actor actor, UUID baseId, UUID extractionId, UUID requestId, String requestHash);
    Document document(Actor actor, UUID id);
    List<Document> documents(Actor actor, UUID baseId);
    Document rebuild(Actor actor, UUID id, UUID requestId, String requestHash);
    void deleteDocument(Actor actor, UUID id, UUID requestId, String requestHash);
    void guard(BuildCommand command);
    void renew(BuildCommand command);
    List<Publication> publications(UUID relayOwner);
    void published(Publication item, boolean confirmed);
    BuildCommand claim(Publication item, UUID owner);
    void insertChunk(BuildCommand command, Chunk chunk);
    void complete(BuildCommand command, int chunkCount, String manifestHash);
    void fail(BuildCommand command, String stableCode);
    long acquireQuerySlot(Actor actor, UUID queryId, UUID ownerId);
    boolean querySlotCurrent(Actor actor, UUID queryId, UUID ownerId, long fence);
    void releaseQuerySlot(UUID queryId, UUID ownerId, long fence);
    List<Hit> search(Actor actor, List<UUID> baseIds, String query, String vector, int limit);
}
