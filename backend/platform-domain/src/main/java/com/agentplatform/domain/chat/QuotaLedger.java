package com.agentplatform.domain.chat;

import java.util.List;
import java.util.UUID;

/** Every operation participates in the caller's short transaction, before execution ownership locks. */
public interface QuotaLedger {
    void lock(UUID user);
    void reserve(UUID user, UUID call, long tokens, long costMicros);
    void settle(UUID user, UUID call, Long tokens, Long costMicros, boolean notSent);
    List<QuotaData.View> view(UUID user);
}
