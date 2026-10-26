package com.agentplatform.infrastructure.dao.po;

import java.time.Instant;
import java.util.UUID;

public final class QuotaRows {
    private QuotaRows() {}
    public record Principal(UUID userId, boolean administrator, boolean active, Instant now) {}
    public record Policy(UUID id, String metric, Long limitValue, String timezone, long version) {}
    public record Bucket(long consumed, long reserved, long version) {}
    public record Reservation(UUID callId, String metric, UUID userId, Instant windowStart,
                              long reserved, Long actual, String status) {}
}
