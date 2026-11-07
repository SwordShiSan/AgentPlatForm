package com.agentplatform.app.capability;

import com.agentplatform.domain.capability.SkillImportRepository;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.infrastructure.dao.capability.SkillImportMapper;
import com.agentplatform.infrastructure.dao.po.SkillImportRow;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/** Short database transactions; calls to object storage are made by the service after the transaction ends. */
public final class TransactionalSkillImportRepository implements SkillImportRepository {
    private final SkillImportMapper mapper;
    private final TransactionTemplate transactions;
    public TransactionalSkillImportRepository(SkillImportMapper mapper, TransactionTemplate transactions) {
        this.mapper = Objects.requireNonNull(mapper); this.transactions = Objects.requireNonNull(transactions);
    }
    private <T> T tx(Supplier<T> action) { return transactions.execute(status -> action.get()); }
    private void access(Actor actor) {
        if (actor == null || !mapper.authorized(actor)) throw new FileFailure(404, "RESOURCE_NOT_FOUND");
    }
    private static Upload view(SkillImportRow row) {
        if (row == null) throw new FileFailure(404, "RESOURCE_NOT_FOUND");
        return new Upload(row.id(), row.targetCapabilityId(), row.targetVersionId(), row.targetAssetId(), row.state(),
                row.declaredBytes(), row.declaredSha256(), row.stagingObjectKey(), row.revision());
    }
    @Override public Upload create(Actor actor, UUID requestId, String requestHash, UUID existingCapability, long bytes, String sha256) {
        if (requestId == null || requestHash == null || !requestHash.matches("[a-f0-9]{64}")
                || sha256 == null || !sha256.matches("[a-f0-9]{64}") || bytes < 0 || bytes > SkillImportService.MAX_ARCHIVE_BYTES)
            throw new FileFailure(400, "INVALID_SKILL_ARCHIVE");
        return tx(() -> {
            if (actor == null) throw new FileFailure(404, "RESOURCE_NOT_FOUND");
            if (mapper.lockQuota(actor) == null) throw new FileFailure(404, "RESOURCE_NOT_FOUND");
            mapper.lock(actor); access(actor);
            var old = mapper.findRequest(actor, requestId);
            if (old != null) {
                if (!old.requestHash().equals(requestHash)) throw new FileFailure(409, "IDEMPOTENCY_CONFLICT");
                return view(old);
            }
            if (existingCapability != null && !mapper.existingSkill(actor, existingCapability))
                throw new FileFailure(404, "RESOURCE_NOT_FOUND");
            if (mapper.reserve(actor, bytes) != 1) throw new FileFailure(422, "STORAGE_QUOTA_EXCEEDED");
            UUID id = UUID.randomUUID(), capability = existingCapability == null ? UUID.randomUUID() : existingCapability;
            UUID version = UUID.randomUUID(), asset = UUID.randomUUID();
            String key = "spaces/" + actor.spaceId() + "/skill-package/" + asset + "/" + version + "/archive";
            mapper.insert(actor, id, requestId, requestHash, capability, version, asset, bytes, sha256, key);
            mapper.reservation(actor, id, bytes);
            return view(mapper.find(actor, id));
        });
    }
    @Override public Upload find(Actor actor, UUID id) { return tx(() -> { access(actor); return view(mapper.find(actor, id)); }); }
    @Override public Upload beginTransfer(Actor actor, UUID id, UUID owner) {
        return tx(() -> { access(actor); if (mapper.begin(actor, id, owner) != 1) throw new FileFailure(409, "SKILL_IMPORT_NOT_UPLOADABLE");
            return view(mapper.find(actor, id)); });
    }
    @Override public void guardTransfer(Actor actor, UUID id, UUID owner) {
        tx(() -> { access(actor); if (mapper.guard(actor, id, owner) != 1) throw new FileFailure(409, "SKILL_IMPORT_TRANSFER_FENCED"); return null; });
    }
    @Override public void verified(Actor actor, UUID id, UUID owner) { finish(actor, id, owner, "VERIFIED"); }
    @Override public void transferUnknown(Actor actor, UUID id, UUID owner) { finish(actor, id, owner, "BLOCKED"); }
    private void finish(Actor actor, UUID id, UUID owner, String state) {
        tx(() -> { access(actor); if (mapper.finish(actor, id, owner, state) != 1) throw new FileFailure(409, "SKILL_IMPORT_TRANSFER_FENCED");
            if ("BLOCKED".equals(state) && mapper.reconcileReservation(actor, id) != 1)
                throw new FileFailure(409, "SKILL_IMPORT_RESERVATION_CONFLICT");
            return null; });
    }
    @Override public Upload enqueue(Actor actor, UUID id, UUID requestId) {
        if (actor == null || requestId == null) throw new FileFailure(400, "INVALID_SKILL_IMPORT_REQUEST");
        return tx(() -> {
            mapper.lock(actor); access(actor);
            var previous = mapper.completion(actor, requestId);
            if (previous != null) {
                if (!previous.equals(id)) throw new FileFailure(409, "IDEMPOTENCY_CONFLICT");
                return view(mapper.find(actor, id));
            }
            var current = view(mapper.find(actor, id));
            if ("QUEUED".equals(current.state())) { mapper.recordCompletion(actor, id, requestId); return current; }
            if (mapper.queue(actor, id) != 1) {
                var latest = view(mapper.find(actor, id));
                if ("QUEUED".equals(latest.state())) { mapper.recordCompletion(actor, id, requestId); return latest; }
                throw new FileFailure(409, "SKILL_IMPORT_NOT_VERIFIED");
            }
            mapper.outbox(actor, id, UUID.randomUUID());
            mapper.recordCompletion(actor, id, requestId);
            return view(mapper.find(actor, id));
        });
    }
}
