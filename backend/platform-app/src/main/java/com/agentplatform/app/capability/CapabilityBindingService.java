package com.agentplatform.app.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository;
import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.CapabilityBindingRepository.BindingSet;
import com.agentplatform.domain.capability.CapabilityBindings;
import com.agentplatform.domain.capability.CapabilityBindings.Selection;
import com.agentplatform.domain.chat.ChatFailure;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import org.springframework.transaction.support.TransactionTemplate;

/** Coordinates versioned binding changes; a workspace can explicitly inherit or select nothing. */
public final class CapabilityBindingService {
    public record Effective(CapabilityBindings.Source source, List<Selection> selections,
                            List<Selection> unavailable) {
        public Effective { selections = List.copyOf(selections); unavailable = List.copyOf(unavailable); }
    }
    private final CapabilityBindingRepository repository;
    private final TransactionTemplate transactions;

    public CapabilityBindingService(CapabilityBindingRepository repository, TransactionTemplate transactions) {
        this.repository = Objects.requireNonNull(repository);
        this.transactions = Objects.requireNonNull(transactions);
    }

    public BindingSet read(Actor actor, UUID workspaceId) {
        return transactions.execute(status -> {
            requireAccess(actor, workspaceId);
            return repository.find(actor, workspaceId);
        });
    }

    /** Returns retained selections and separately identifies versions that are no longer usable. */
    public Effective effective(Actor actor, UUID workspaceId) {
        return transactions.execute(status -> {
            requireAccess(actor, workspaceId);
            BindingSet personal = repository.find(actor, null);
            BindingSet workspace = workspaceId == null ? null : repository.find(actor, workspaceId);
            var resolved = CapabilityBindings.resolve(personal == null ? List.of() : personal.selections(),
                    workspace == null || workspace.inheritPersonal() ? null : workspace.selections());
            var unavailable = resolved.selections().stream().filter(s -> !repository.published(actor, s)).toList();
            return new Effective(resolved.source(), resolved.selections(), unavailable);
        });
    }

    public BindingSet replace(Actor actor, UUID workspaceId, long expected, boolean inherit,
                              List<Selection> selections) {
        return replace(actor, workspaceId, expected, inherit, selections, UUID.randomUUID(), UUID.randomUUID());
    }

    public BindingSet replace(Actor actor, UUID workspaceId, long expected, boolean inherit,
                              List<Selection> selections, UUID requestId, UUID traceId) {
        if (actor == null || expected < 0 || selections == null || (workspaceId == null && inherit)
                || (inherit && !selections.isEmpty()) || requestId == null || traceId == null)
            throw new ChatFailure(400, "INVALID_CAPABILITY_BINDINGS");
        try { CapabilityBindings.resolve(selections, null); }
        catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ChatFailure(422, "INVALID_CAPABILITY_BINDINGS");
        }
        String requestHash = requestHash(workspaceId, expected, inherit, selections);
        return transactions.execute(status -> {
            requireAccess(actor, workspaceId);
            repository.lock(actor, workspaceId);
            // Check again under the serialization lock before accepting any version.
            requireAccess(actor, workspaceId);
            var accepted = repository.accepted(actor, requestId);
            if (accepted != null) {
                if (!accepted.requestHash().equals(requestHash)) throw new ChatFailure(409, "IDEMPOTENCY_CONFLICT");
                return accepted.result();
            }
            for (Selection selection : selections) {
                if (!repository.published(actor, selection)) throw new ChatFailure(422, "CAPABILITY_VERSION_UNAVAILABLE");
            }
            repository.initialize(actor, workspaceId, UUID.randomUUID());
            BindingSet current = repository.find(actor, workspaceId);
            if (current == null || !repository.replace(actor, current.id(), expected, inherit, selections))
                throw new ChatFailure(412, "CAPABILITY_BINDING_VERSION_CONFLICT");
            BindingSet result = repository.find(actor, workspaceId);
            repository.recordAccepted(actor, requestId, requestHash, result, traceId);
            return result;
        });
    }

    private static String requestHash(UUID workspaceId, long expected, boolean inherit, List<Selection> selections) {
        StringBuilder canonical = new StringBuilder("capability-bindings-v1\n")
                .append(workspaceId == null ? "personal" : workspaceId).append('\n')
                .append(expected).append('\n').append(inherit).append('\n');
        selections.stream().sorted(Comparator.comparing(s -> s.capabilityId().toString())).forEach(s ->
                canonical.append(s.capabilityId()).append(':').append(s.versionId()).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public com.agentplatform.api.capability.CapabilityBindingsApi.Bindings read(
            com.agentplatform.api.chat.ChatApi.Caller caller, String workspaceId) {
        var value = read(actor(caller), optionalUuid(workspaceId));
        return view(value, workspaceId != null);
    }

    public com.agentplatform.api.capability.CapabilityBindingsApi.Bindings replace(
            com.agentplatform.api.chat.ChatApi.Caller caller, String workspaceId, long expected,
            com.agentplatform.api.capability.CapabilityBindingsApi.Update update, String requestId) {
        if (update == null || update.selections() == null) throw new ChatFailure(400, "INVALID_CAPABILITY_BINDINGS");
        List<Selection> selections = update.selections().stream().map(s -> {
            if (s == null) throw new ChatFailure(400, "INVALID_CAPABILITY_BINDINGS");
            return new Selection(uuid(s.capabilityId()), uuid(s.versionId()));
        }).toList();
        var result = replace(actor(caller), optionalUuid(workspaceId), expected, update.inheritPersonal(),
                selections, uuid(requestId), com.agentplatform.types.RequestTrace.current());
        return view(result, workspaceId != null);
    }

    public com.agentplatform.api.capability.CapabilityBindingsApi.Effective effective(
            com.agentplatform.api.chat.ChatApi.Caller caller, String workspaceId) {
        var result = effective(actor(caller), optionalUuid(workspaceId));
        return new com.agentplatform.api.capability.CapabilityBindingsApi.Effective(result.source().name(),
                project(result.selections()), project(result.unavailable()));
    }

    private static com.agentplatform.api.capability.CapabilityBindingsApi.Bindings view(BindingSet value, boolean workspace) {
        return value == null ? new com.agentplatform.api.capability.CapabilityBindingsApi.Bindings(0, workspace, List.of())
                : new com.agentplatform.api.capability.CapabilityBindingsApi.Bindings(value.revision(), value.inheritPersonal(), project(value.selections()));
    }
    private static List<com.agentplatform.api.capability.CapabilityBindingsApi.Selection> project(List<Selection> values) {
        return values.stream().map(s -> new com.agentplatform.api.capability.CapabilityBindingsApi.Selection(
                s.capabilityId().toString(), s.versionId().toString())).toList();
    }
    private static Actor actor(com.agentplatform.api.chat.ChatApi.Caller caller) {
        if (caller == null) throw new ChatFailure(401, "AUTHENTICATION_REQUIRED");
        return new Actor(uuid(caller.userId()), uuid(caller.spaceId()));
    }
    private static UUID optionalUuid(String value) { return value == null ? null : uuid(value); }
    private static UUID uuid(String value) {
        try { return UUID.fromString(value); } catch (RuntimeException invalid) { throw new ChatFailure(400, "INVALID_UUID"); }
    }

    private void requireAccess(Actor actor, UUID workspaceId) {
        if (actor == null || !repository.authorized(actor, workspaceId)) throw new ChatFailure(404, "RESOURCE_NOT_FOUND");
    }
}
