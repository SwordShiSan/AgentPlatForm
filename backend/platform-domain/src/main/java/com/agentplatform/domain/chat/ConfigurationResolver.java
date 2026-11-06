package com.agentplatform.domain.chat;

import com.agentplatform.types.TaskPermissionData.Mode;
import java.util.Objects;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** Pure resolution of task defaults; authorization remains a separate live check. */
public final class ConfigurationResolver {
    public enum CredentialSource { PLATFORM, USER }
    public enum Source { PLATFORM, USER, WORKSPACE, REQUEST }
    public enum Field { MODEL_ID, CREDENTIAL_SOURCE, PLAN_MODE, PERMISSION_MODE }

    /** Null means no value at this layer; a Boolean false is an explicit value. */
    public record Layer(UUID modelId, CredentialSource credentialSource, Boolean planMode, Mode permissionMode) {}
    public record Value<T>(T value, Source source) {}
    public record Resolved(Value<UUID> modelId, Value<CredentialSource> credentialSource,
                           Value<Boolean> planMode, Value<Mode> permissionMode, Set<Field> lockedFields) {
        public Resolved { lockedFields = Set.copyOf(lockedFields); }
    }

    private ConfigurationResolver() {}

    public static Resolved resolve(Layer platform, Layer user, Layer workspace, Layer request) {
        return resolve(platform, user, workspace, request, null);
    }

    /** Locked defaults are separate from live authorization and mandatory risk rules. */
    public static Resolved resolve(Layer platform, Layer user, Layer workspace, Layer request, Layer locked) {
        Objects.requireNonNull(platform, "platform");
        Layer empty = new Layer(null, null, null, null);
        user = user == null ? empty : user;
        workspace = workspace == null ? empty : workspace;
        request = request == null ? empty : request;
        locked = locked == null ? empty : locked;
        var fields = EnumSet.noneOf(Field.class);
        if (locked.modelId() != null) fields.add(Field.MODEL_ID);
        if (locked.credentialSource() != null) fields.add(Field.CREDENTIAL_SOURCE);
        if (locked.planMode() != null) fields.add(Field.PLAN_MODE);
        if (locked.permissionMode() != null) fields.add(Field.PERMISSION_MODE);
        return new Resolved(
            choose(platform.modelId(), user.modelId(), workspace.modelId(), request.modelId(), locked.modelId()),
            choose(platform.credentialSource(), user.credentialSource(), workspace.credentialSource(), request.credentialSource(), locked.credentialSource()),
            choose(platform.planMode(), user.planMode(), workspace.planMode(), request.planMode(), locked.planMode()),
            choose(platform.permissionMode(), user.permissionMode(), workspace.permissionMode(), request.permissionMode(), locked.permissionMode()), fields);
    }

    private static <T> Value<T> choose(T platform, T user, T workspace, T request, T locked) {
        if (locked != null) {
            if (request != null && !request.equals(locked)) throw new ChatFailure(422, "CONFIG_CONSTRAINT_VIOLATION");
            return new Value<>(locked, Source.PLATFORM);
        }
        if (request != null) return new Value<>(request, Source.REQUEST);
        if (workspace != null) return new Value<>(workspace, Source.WORKSPACE);
        if (user != null) return new Value<>(user, Source.USER);
        return new Value<>(Objects.requireNonNull(platform, "platform default"), Source.PLATFORM);
    }
}
