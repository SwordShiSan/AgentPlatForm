package com.agentplatform.infrastructure.dao.po;
import java.time.Instant;
import java.util.UUID;
public final class ChatProjections {
    private ChatProjections() {}
    public interface TaskProjection { UUID id(); UUID conversationId(); String status(); Boolean cancelRequested(); String reasonCode(); Long version(); }
    public interface ConversationProjection { UUID id(); String title(); Long version(); Instant updatedAt(); }
}
