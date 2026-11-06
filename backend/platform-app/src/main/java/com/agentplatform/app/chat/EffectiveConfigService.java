package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.ConfigurationResolver;
import com.agentplatform.domain.chat.WorkspaceConfigurationRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/** Coordinates short, versioned workspace configuration transactions. */
public final class EffectiveConfigService implements com.agentplatform.api.chat.WorkspaceSettingsApi {
    private final WorkspaceConfigurationRepository repository;
    private final TransactionTemplate transactions;
    private final ConfigurationResolver.Layer locked;

    public EffectiveConfigService(WorkspaceConfigurationRepository repository, TransactionTemplate transactions,
                                  ConfigurationResolver.Layer locked) {
        this.repository = Objects.requireNonNull(repository);
        this.transactions = Objects.requireNonNull(transactions);
        this.locked = locked == null ? new ConfigurationResolver.Layer(null, null, null, null) : locked;
    }

    public WorkspaceConfigurationRepository.View read(WorkspaceConfigurationRepository.Actor actor, UUID workspace) {
        return transactions.execute(status -> {
            requireAccess(actor, workspace);
            return repository.find(actor, workspace);
        });
    }

    public WorkspaceConfigurationRepository.View update(WorkspaceConfigurationRepository.Actor actor, UUID workspace,
                                                          long expected, WorkspaceConfigurationRepository.Patch patch,
                                                          UUID traceId) {
        if (expected < 0 || patch == null || patch.isEmpty() || traceId == null) throw new ChatFailure(400, "INVALID_CONFIGURATION_PATCH");
        return transactions.execute(status -> {
            requireAccess(actor, workspace);
            repository.lockConfiguration(actor,workspace);
            if (patch.modelPresent() && patch.modelId() != null && !repository.modelEnabled(patch.modelId()))
                throw new ChatFailure(422, "MODEL_CAPABILITY_MISMATCH");
            requireUnlocked(patch.modelPresent(), patch.modelId(), locked.modelId());
            requireUnlocked(patch.credentialPresent(), patch.credentialSource(), locked.credentialSource());
            requireUnlocked(patch.planPresent(), patch.planMode(), locked.planMode());
            requireUnlocked(patch.permissionPresent(), patch.permissionMode(), locked.permissionMode());
            repository.initialize(actor, workspace);
            if (!repository.update(actor, workspace, expected, patch)) throw new ChatFailure(412, "WORKSPACE_CONFIGURATION_VERSION_CONFLICT");
            repository.audit(actor, workspace, traceId);
            return repository.find(actor, workspace);
        });
    }

    private void requireAccess(WorkspaceConfigurationRepository.Actor actor, UUID workspace) {
        if (actor == null || workspace == null || !repository.authorized(actor, workspace))
            throw new ChatFailure(404, "RESOURCE_NOT_FOUND");
    }

    private static <T> void requireUnlocked(boolean present, T value, T locked) {
        if (present && value != null && locked != null && !value.equals(locked))
            throw new ChatFailure(422, "CONFIG_CONSTRAINT_VIOLATION");
    }

    @Override public Settings read(com.agentplatform.api.chat.ChatApi.Caller caller, String workspaceId) {
        return view(read(actor(caller), uuid(workspaceId)));
    }

    @Override public Settings update(com.agentplatform.api.chat.ChatApi.Caller caller, String workspaceId,
                                     long expected, Patch patch) {
        if (patch == null) throw new ChatFailure(400, "INVALID_CONFIGURATION_PATCH");
        ConfigurationResolver.CredentialSource source = parseSource(patch.credentialSource());
        com.agentplatform.types.TaskPermissionData.Mode mode = parseMode(patch.permissionMode());
        var converted = new WorkspaceConfigurationRepository.Patch(patch.modelPresent(),
                patch.modelId() == null ? null : uuid(patch.modelId()), patch.credentialPresent(), source,
                patch.planPresent(), patch.planMode(), patch.permissionPresent(), mode);
        return view(update(actor(caller), uuid(workspaceId), expected, converted, com.agentplatform.types.RequestTrace.current()));
    }

    private static Settings view(WorkspaceConfigurationRepository.View view) {
        var values = view.overrides();
        return new Settings(view.version(), new Values(values.modelId() == null ? null : values.modelId().toString(),
                values.credentialSource() == null ? null : values.credentialSource().name(), values.planMode(),
                values.permissionMode() == null ? null : values.permissionMode().name()));
    }
    private static WorkspaceConfigurationRepository.Actor actor(com.agentplatform.api.chat.ChatApi.Caller caller) {
        if (caller == null) throw new ChatFailure(401, "AUTHENTICATION_REQUIRED");
        return new WorkspaceConfigurationRepository.Actor(uuid(caller.userId()), uuid(caller.spaceId()));
    }
    private static UUID uuid(String value) {
        try { return UUID.fromString(value); } catch (RuntimeException invalid) { throw new ChatFailure(400, "INVALID_UUID"); }
    }
    private static ConfigurationResolver.CredentialSource parseSource(String value) {
        try { return value == null ? null : ConfigurationResolver.CredentialSource.valueOf(value); }
        catch (IllegalArgumentException invalid) { throw new ChatFailure(422, "INVALID_CREDENTIAL_SOURCE"); }
    }
    private static com.agentplatform.types.TaskPermissionData.Mode parseMode(String value) {
        try { return value == null ? null : com.agentplatform.types.TaskPermissionData.Mode.valueOf(value); }
        catch (IllegalArgumentException invalid) { throw new ChatFailure(422, "INVALID_PERMISSION_MODE"); }
    }
}
