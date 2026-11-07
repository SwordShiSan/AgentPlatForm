package com.agentplatform.app.capability;

import com.agentplatform.domain.capability.SkillValidationRepository;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.infrastructure.dao.capability.SkillWorkerMapper;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Fenced worker transactions; no storage or messaging operations occur while holding database locks. */
public final class TransactionalSkillValidationRepository implements SkillValidationRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final SkillWorkerMapper mapper;
    private final TransactionTemplate transactions;
    public TransactionalSkillValidationRepository(SkillWorkerMapper mapper, TransactionTemplate transactions) {
        this.mapper = Objects.requireNonNull(mapper); this.transactions = Objects.requireNonNull(transactions);
    }
    private <T> T tx(Supplier<T> action) { return transactions.execute(status -> action.get()); }
    private void requireAccess(UUID actor, UUID space) {
        if (!mapper.authorized(actor, space)) throw new FileFailure(403, "SKILL_IMPORT_AUTHORIZATION_REVOKED");
    }
    private static void exactlyOne(int count) { if (count != 1) throw new FileFailure(409, "SKILL_IMPORT_STALE_OWNER"); }
    @Override public Publication nextPublication(UUID owner) { return tx(() -> mapper.next(owner)); }
    @Override public void published(Publication publication) { tx(() -> { exactlyOne(mapper.published(publication)); return null; }); }
    @Override public Command claim(Publication publication, UUID owner) {
        return tx(() -> {
            var row = mapper.byPublication(publication);
            if (row == null) throw new FileFailure(409, "SKILL_IMPORT_DELIVERY_UNKNOWN");
            if (mapper.inbox(publication) == 0) return null;
            if (!mapper.authorized(row.ownerUserId(), row.spaceId())) {
                exactlyOne(mapper.blockQueued(publication)); exactlyOne(mapper.reconcileQueued(publication)); return null;
            }
            long fence = row.revision() + 1;
            exactlyOne(mapper.claim(publication, owner, fence));
            return new Command(row.spaceId(), row.ownerUserId(), row.id(), owner, row.generation(), fence,
                    row.stagingObjectKey(), row.declaredBytes(), row.declaredSha256());
        });
    }
    @Override public void guard(Command command) {
        tx(() -> { requireAccess(command.actorId(), command.spaceId()); exactlyOne(mapper.guard(command)); return null; });
    }
    @Override public void draft(Command command, Manifest manifest) {
        if (!command.sha256().equals(manifest.archiveSha256()) || manifest.files().isEmpty() || manifest.files().size() > 1000
                || manifest.expandedBytes() < 0 || manifest.expandedBytes() > 100L * 1024 * 1024)
            throw new FileFailure(409, "SKILL_MANIFEST_MISMATCH");
        tx(() -> {
            if (mapper.lockQuota(command.spaceId()) == null) throw new FileFailure(404, "RESOURCE_NOT_FOUND");
            requireAccess(command.actorId(), command.spaceId());
            var row = mapper.byCommand(command);
            if (row == null) throw new FileFailure(409, "SKILL_IMPORT_STALE_OWNER");
            mapper.insertCapability(row);
            if (mapper.lockCapability(row.spaceId(), row.ownerUserId(), row.targetCapabilityId()) == null)
                throw new FileFailure(409, "SKILL_CAPABILITY_MISMATCH");
            long version = mapper.nextVersion(row);
            String filesJson = JSON.writeValueAsString(manifest.files());
            exactlyOne(mapper.insertVersion(row, version, manifestHash(manifest, filesJson)));
            exactlyOne(mapper.insertAsset(row));
            exactlyOne(mapper.insertPackage(row, manifest.name(), manifest.description(), manifest.expandedBytes(),
                    manifest.files().size(), filesJson, manifest.dependenciesJson()));
            exactlyOne(mapper.insertStatus(row)); exactlyOne(mapper.settleQuota(command));
            exactlyOne(mapper.settleReservation(command)); exactlyOne(mapper.finish(command));
            exactlyOne(mapper.audit(command, UUID.randomUUID()));
            return null;
        });
    }
    private static String manifestHash(Manifest manifest, String filesJson) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                for (String part : new String[]{"skill-manifest-v1", manifest.archiveSha256(), manifest.name(), manifest.description(),
                        Long.toString(manifest.expandedBytes()), filesJson, manifest.dependenciesJson()}) {
                    byte[] encoded = part.getBytes(StandardCharsets.UTF_8); output.writeInt(encoded.length); output.write(encoded);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Override public void rejected(Command command, String reason) { block(command, "REJECTED", reason); }
    @Override public void unknown(Command command, String reason) { block(command, "BLOCKED", reason); }
    @Override public void quarantine(UUID id, String bodyHash) { tx(() -> { exactlyOne(mapper.quarantine(id, bodyHash)); return null; }); }
    private void block(Command command, String state, String reason) {
        if (reason == null || !reason.matches("[A-Z0-9_]{1,64}")) throw new IllegalArgumentException("INVALID_REASON");
        tx(() -> {
            if (mapper.lockQuota(command.spaceId()) == null) throw new FileFailure(404, "RESOURCE_NOT_FOUND");
            if (mapper.byCommand(command) == null) throw new FileFailure(409, "SKILL_IMPORT_STALE_OWNER");
            exactlyOne(mapper.block(command, state, reason)); exactlyOne(mapper.reconciling(command)); return null;
        });
    }
}
