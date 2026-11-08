package com.agentplatform.domain.chat;

import com.agentplatform.domain.chat.ChatData.Actor;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.types.harness.HarnessData.Bundle;
import com.agentplatform.types.harness.HarnessData.Message;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable, immutable context projections; canonical task facts are never rewritten. */
public interface ContextProjectionStore {
    record Projection(long epoch, String bundleHash, String ledgerWatermark, String sourceManifestSha256,
                      String summarySha256, List<Message> messages, long estimatedInputTokens,
                      String policyVersion, Instant createdAt) {
        public Projection { messages = List.copyOf(messages); }
    }
    record Summary(UUID taskId, long epoch, String bundleHash, String sourceManifestSha256,
                   String summarySha256, long estimatedInputTokens, String policyVersion, Instant createdAt) {}

    Optional<Projection> current(Lease lease, String bundleHash, String ledgerWatermark,
                                 String sourceManifestSha256);
    Projection saveIfWatermarkCurrent(Lease lease, Bundle bundle, String ledgerWatermark, String sourceManifestSha256,
                                     String summarySha256, List<Message> messages, long estimatedInputTokens,
                                     String policyVersion);
    Summary describe(Actor actor, UUID taskId);
}
