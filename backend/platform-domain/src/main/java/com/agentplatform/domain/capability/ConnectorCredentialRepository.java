package com.agentplatform.domain.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API operations expose metadata only; secret material is supplied solely to the insert operation. */
public interface ConnectorCredentialRepository {
    record Metadata(UUID id, long revision, String state, Instant createdAt) {}
    record Creation(String fingerprint, Metadata metadata) {}
    /** Trusted call-time ciphertext; returned only for the exact active revision. */
    record Use(UUID credentialId, long revision, ConnectorCiphertext encrypted) {}
    record Revocation(UUID credentialId, long expectedRevision, long resultRevision) {}
    boolean authorized(Actor actor);
    void lock(Actor actor);
    Creation findRequest(Actor actor, UUID requestId);
    Metadata find(Actor actor, UUID id, boolean lock);
    Use forUse(Actor actor, UUID id, long expectedRevision);
    List<Metadata> list(Actor actor, UUID after, int limit);
    void insert(Actor actor, UUID requestId, String fingerprint, ConnectorCiphertext encrypted);
    Revocation findRevocation(Actor actor, UUID requestId);
    boolean revoke(Actor actor, UUID id, long expected);
    void recordRevocation(Actor actor, UUID requestId, UUID id, long expected, UUID trace);
    void auditCreation(Actor actor, UUID id, UUID trace);
}
