package com.agentplatform.infrastructure.health;

import com.agentplatform.domain.health.ReadinessProbe;
import com.agentplatform.infrastructure.storage.PostgresStateStorage;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/** Baseline DB schema and dependency transport reachability, not model/task readiness. */
public final class DependencyReadinessProbe implements ReadinessProbe {
    public record Endpoint(String host, int port) {}
    private final PostgresStateStorage storage;
    private final List<Endpoint> endpoints;

    public DependencyReadinessProbe(PostgresStateStorage storage, List<Endpoint> endpoints) {
        this.storage = storage;
        this.endpoints = List.copyOf(endpoints);
    }

    @Override public boolean ready() {
        try (var connection = storage.connect(); var query = connection.createStatement()) {
            query.setQueryTimeout(2);
            try (var rows = query.executeQuery("SELECT schema_version FROM pf.baseline_version WHERE id=1")) {
                if (!rows.next() || rows.getInt(1) != 1) return false;
            }
            for (Endpoint endpoint : endpoints) {
                try (var socket = new Socket()) {
                    socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), 500);
                }
            }
            return true;
        } catch (Exception unavailable) { return false; }
    }
}
