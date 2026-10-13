package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.domain.harness.HarnessPorts.StateStorage;
import com.agentplatform.types.harness.HarnessData.Scope;
import io.agentscope.core.state.*;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JacksonJsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** SDK private state with explicit scope and CAS. The codec instance is not a global override. */
public final class ScopeStateStore implements AgentStateStore {
    private final StateStorage storage;
    private final Scope scope;
    private final JsonCodec codec = new JacksonJsonCodec();

    public ScopeStateStore(StateStorage storage, Scope scope) { this.storage = storage; this.scope = scope; }
    private void authorize(String user, String session) {
        if (!scope.actorId().equals(user) || !scope.conversationId().equals(session)) throw new SecurityException("STATE_SCOPE_MISMATCH");
    }
    private byte[] bytes(Object value) { return codec.toJson(value).getBytes(StandardCharsets.UTF_8); }
    private <T> T bound(T value) {
        if (value instanceof AgentState state && (!scope.actorId().equals(state.getUserId()) || !scope.conversationId().equals(state.getSessionId()))) throw new SecurityException("STATE_SCOPE_MISMATCH");
        return value;
    }
    @Override public boolean supportsVersioning() { return true; }
    @Override public void save(String user, String session, String key, State value) {
        authorize(user, session); storage.compareAndSwap(scope, key, -1, bytes(bound(value)));
    }
    @Override public void save(String user, String session, String key, List<? extends State> values) {
        authorize(user, session); storage.compareAndSwap(scope, key, -1, bytes(values));
    }
    @Override public long saveIfVersion(String user, String session, String key, State value, long expected) {
        authorize(user, session);
        long version = storage.compareAndSwap(scope, key, expected, bytes(bound(value)));
        if (version < 0) throw new IllegalStateException("STATE_CAS_CONFLICT");
        return version;
    }
    @Override public <T extends State> VersionedState<T> getVersioned(String user, String session, String key, Class<T> type) {
        authorize(user, session);
        var slot = storage.load(scope, key);
        return new VersionedState<>(slot.body() == null ? null : bound(codec.fromJson(new String(slot.body(), StandardCharsets.UTF_8), type)), slot.version());
    }
    @Override public <T extends State> Optional<T> get(String user, String session, String key, Class<T> type) {
        return Optional.ofNullable(getVersioned(user, session, key, type).value());
    }
    @Override public <T extends State> List<T> getList(String user, String session, String key, Class<T> type) {
        authorize(user, session);
        var body = storage.load(scope, key).body();
        if (body == null) return List.of();
        List<Object> values = codec.fromJson(new String(body, StandardCharsets.UTF_8), new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {});
        return values.stream().map(value -> codec.convertValue(value, type)).toList();
    }
    @Override public boolean exists(String user, String session) { authorize(user, session); return !storage.keys(scope).isEmpty(); }
    @Override public void delete(String user, String session) { authorize(user, session); storage.keys(scope).forEach(key -> storage.delete(scope, key)); }
    @Override public void delete(String user, String session, String key) { authorize(user, session); storage.delete(scope, key); }
    @Override public Set<String> listSessionIds(String user) {
        if (!scope.actorId().equals(user)) throw new SecurityException("STATE_SCOPE_MISMATCH");
        return storage.keys(scope).isEmpty() ? Set.of() : Set.of(scope.conversationId());
    }
}
