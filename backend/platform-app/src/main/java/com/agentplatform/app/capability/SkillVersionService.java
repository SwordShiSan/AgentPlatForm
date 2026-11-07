package com.agentplatform.app.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.SkillVersionRepository;
import com.agentplatform.domain.capability.SkillVersionRepository.Command;
import com.agentplatform.domain.capability.SkillVersionRepository.View;
import com.agentplatform.domain.files.FileFailure;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/** Publication is explicit and cannot claim unresolved dependencies are installed. */
public final class SkillVersionService {
    private final SkillVersionRepository repository;
    private final TransactionTemplate transactions;
    public SkillVersionService(SkillVersionRepository repository, TransactionTemplate transactions) {
        this.repository = Objects.requireNonNull(repository); this.transactions = Objects.requireNonNull(transactions);
    }
    public View get(Actor actor, UUID capability, UUID version) {
        return transactions.execute(status -> { access(actor); return requireView(actor, capability, version, false); });
    }
    /** Internal selection only; the caller must also check its task lease and bindings. */
    SkillVersionRepository.MaterialSource material(Actor actor, UUID capability, UUID version) {
        return transactions.execute(status -> {
            access(actor);
            var source = repository.material(actor, capability, version);
            if (source == null) throw new FileFailure(404, "SKILL_MATERIAL_UNAVAILABLE");
            return source;
        });
    }
    public View change(Actor actor, UUID capability, UUID version, String action, long expected, UUID request, UUID trace) {
        if (actor == null || capability == null || version == null || request == null || trace == null || expected < 1
                || !("PUBLISH".equals(action) || "REVOKE".equals(action))) throw new FileFailure(400, "INVALID_SKILL_VERSION_COMMAND");
        return transactions.execute(status -> {
            repository.lock(actor); access(actor);
            var previous = repository.accepted(actor, request);
            if (previous != null) {
                if (!previous.capabilityId().equals(capability) || !previous.versionId().equals(version)
                        || !previous.action().equals(action) || previous.expectedRevision() != expected)
                    throw new FileFailure(409, "IDEMPOTENCY_CONFLICT");
                View current = requireView(actor, capability, version, false);
                return new View(capability, version, previous.resultState(), previous.resultRevision(), current.name(),
                        current.description(), current.manifestJson(), current.dependenciesJson(), current.assetAvailable());
            }
            View current = requireView(actor, capability, version, true);
            if (current.revision() != expected) throw new FileFailure(412, "SKILL_VERSION_CONFLICT");
            if ("PUBLISH".equals(action)) {
                if (!"DRAFT".equals(current.state())) throw new FileFailure(409, "SKILL_VERSION_NOT_DRAFT");
                if (!current.assetAvailable()) throw new FileFailure(409, "SKILL_ASSET_UNAVAILABLE");
                if (!"[]".equals(current.dependenciesJson().trim())) throw new FileFailure(422, "SKILL_DEPENDENCIES_UNRESOLVED");
            } else if (!"DRAFT".equals(current.state()) && !"PUBLISHED".equals(current.state())) {
                throw new FileFailure(409, "SKILL_VERSION_ALREADY_REVOKED");
            }
            String target = "PUBLISH".equals(action) ? "PUBLISHED" : "REVOKED";
            if (!repository.transition(actor, capability, version, expected, current.state(), target))
                throw new FileFailure(412, "SKILL_VERSION_CONFLICT");
            repository.record(actor, new Command(request, capability, version, action, expected, expected+1, target), trace);
            return requireView(actor, capability, version, false);
        });
    }
    private void access(Actor actor) {
        if (actor == null || !repository.authorized(actor)) throw new FileFailure(404, "RESOURCE_NOT_FOUND");
    }
    private View requireView(Actor actor, UUID capability, UUID version, boolean lock) {
        var view = repository.find(actor, capability, version, lock);
        if (view == null) throw new FileFailure(404, "RESOURCE_NOT_FOUND");
        return view;
    }
}
