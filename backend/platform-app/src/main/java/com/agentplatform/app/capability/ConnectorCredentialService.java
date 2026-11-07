package com.agentplatform.app.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.ConnectorCredentialRepository;
import com.agentplatform.domain.capability.ConnectorCredentialRepository.Metadata;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.infrastructure.chat.CredentialCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.transaction.support.TransactionTemplate;

/** The configured key is dedicated to MCP credentials; no secret enters returned metadata. */
public final class ConnectorCredentialService {
    public record Page(List<Metadata> items, UUID nextCursor) { public Page { items = List.copyOf(items); } }
    private final ConnectorCredentialRepository repository;
    private final TransactionTemplate transactions;
    private final CredentialCipher cipher;
    private final byte[] fingerprintKey;
    public ConnectorCredentialService(ConnectorCredentialRepository repository, TransactionTemplate transactions, byte[] masterKey) {
        this.repository = Objects.requireNonNull(repository); this.transactions = Objects.requireNonNull(transactions);
        if (masterKey == null || masterKey.length != 32) throw new IllegalArgumentException("MCP master key must contain 32 bytes");
        this.cipher = new CredentialCipher(masterKey);
        this.fingerprintKey = hmac(masterKey, "mcp-request-fingerprint-key-v1".getBytes(StandardCharsets.UTF_8));
    }
    public Metadata create(Actor actor, UUID requestId, String secret, UUID trace) {
        if (actor == null || requestId == null || trace == null || secret == null || secret.isBlank()
                || secret.getBytes(StandardCharsets.UTF_8).length > 8192 || secret.indexOf('\r') >= 0 || secret.indexOf('\n') >= 0)
            throw new FileFailure(400, "CONNECTOR_CREDENTIAL_INVALID");
        String fingerprint = fingerprint(actor, "create", secret);
        return transactions.execute(status -> {
            repository.lock(actor); access(actor);
            var existing = repository.findRequest(actor, requestId);
            if (existing != null) {
                if (!MessageDigest.isEqual(existing.fingerprint().getBytes(StandardCharsets.US_ASCII), fingerprint.getBytes(StandardCharsets.US_ASCII)))
                    throw new FileFailure(409, "IDEMPOTENCY_CONFLICT");
                return existing.metadata();
            }
            UUID id = UUID.randomUUID();
            var encrypted = cipher.encryptConnector(id, actor.spaceId(), actor.userId(), secret);
            repository.insert(actor, requestId, fingerprint, encrypted); repository.auditCreation(actor, id, trace);
            return required(actor, id, false);
        });
    }
    public Metadata replace(Actor actor, UUID oldId, long expected, UUID requestId, String secret, UUID trace) {
        if (actor == null || oldId == null || expected < 1 || requestId == null || trace == null || secret == null || secret.isBlank()
                || secret.getBytes(StandardCharsets.UTF_8).length > 8192 || secret.indexOf('\r') >= 0 || secret.indexOf('\n') >= 0)
            throw new FileFailure(400, "CONNECTOR_CREDENTIAL_INVALID");
        String fingerprint = fingerprint(actor, "replace:" + oldId + ":" + expected, secret);
        return transactions.execute(status -> {
            repository.lock(actor); access(actor);
            var existing = repository.findRequest(actor, requestId); var prior = repository.findRevocation(actor, requestId);
            if (existing != null || prior != null) {
                if (existing == null || prior == null || !prior.credentialId().equals(oldId) || prior.expectedRevision() != expected
                        || !MessageDigest.isEqual(existing.fingerprint().getBytes(StandardCharsets.US_ASCII), fingerprint.getBytes(StandardCharsets.US_ASCII)))
                    throw new FileFailure(409, "IDEMPOTENCY_CONFLICT");
                return existing.metadata();
            }
            var current = required(actor, oldId, true);
            if (current.revision() != expected) throw new FileFailure(412, "CONNECTOR_CREDENTIAL_VERSION_CONFLICT");
            if (!"ACTIVE".equals(current.state())) throw new FileFailure(409, "CONNECTOR_CREDENTIAL_REVOKED");
            UUID next = UUID.randomUUID();
            repository.insert(actor, requestId, fingerprint, cipher.encryptConnector(next, actor.spaceId(), actor.userId(), secret));
            if (!repository.revoke(actor, oldId, expected)) throw new FileFailure(412, "CONNECTOR_CREDENTIAL_VERSION_CONFLICT");
            repository.recordRevocation(actor, requestId, oldId, expected, trace); repository.auditCreation(actor, next, trace);
            return required(actor, next, false);
        });
    }
    public Page list(Actor actor, UUID after, int limit) {
        if (limit < 1 || limit > 100) throw new FileFailure(400, "INVALID_PAGE_SIZE");
        return transactions.execute(status -> {
            access(actor); var rows = repository.list(actor, after, limit + 1); var page = rows.subList(0, Math.min(limit, rows.size()));
            return new Page(page, rows.size() > limit ? page.getLast().id() : null);
        });
    }
    public Metadata revoke(Actor actor, UUID id, long expected, UUID requestId, UUID trace) {
        if (actor == null || id == null || expected < 1 || requestId == null || trace == null)
            throw new FileFailure(400, "CONNECTOR_CREDENTIAL_INVALID");
        return transactions.execute(status -> {
            repository.lock(actor); access(actor);
            var prior = repository.findRevocation(actor, requestId);
            if (prior != null) {
                if (!prior.credentialId().equals(id) || prior.expectedRevision() != expected) throw new FileFailure(409, "IDEMPOTENCY_CONFLICT");
                var current = required(actor, id, false);
                return new Metadata(id, prior.resultRevision(), "REVOKED", current.createdAt());
            }
            var current = required(actor, id, true);
            if (current.revision() != expected) throw new FileFailure(412, "CONNECTOR_CREDENTIAL_VERSION_CONFLICT");
            if (!"ACTIVE".equals(current.state())) throw new FileFailure(409, "CONNECTOR_CREDENTIAL_REVOKED");
            if (!repository.revoke(actor, id, expected)) throw new FileFailure(412, "CONNECTOR_CREDENTIAL_VERSION_CONFLICT");
            repository.recordRevocation(actor, requestId, id, expected, trace);
            return required(actor, id, false);
        });
    }
    private String fingerprint(Actor actor, String operation, String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(fingerprintKey, "HmacSHA256"));
            mac.update(("mcp-secret-v1\n" + actor.spaceId() + "\n" + actor.userId() + "\n" + operation + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(bytes));
        } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }
    private static byte[] hmac(byte[] key, byte[] message) {
        try { var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(message); }
        catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    private void access(Actor actor) { if (actor == null || !repository.authorized(actor)) throw new FileFailure(404, "RESOURCE_NOT_FOUND"); }
    private Metadata required(Actor actor, UUID id, boolean lock) {
        var value = repository.find(actor, id, lock); if (value == null) throw new FileFailure(404, "RESOURCE_NOT_FOUND"); return value;
    }
}
