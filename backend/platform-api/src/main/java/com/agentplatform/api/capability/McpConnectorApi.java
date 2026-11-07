package com.agentplatform.api.capability;

import com.agentplatform.api.chat.ChatApi;
import java.util.List;

public interface McpConnectorApi {
    record Input(String endpoint, String authKind, String apiKeyHeader, String credentialId, Long credentialRevision) {}
    record Configuration(String id, String endpoint, String authKind, String apiKeyHeader, String credentialId, Long credentialRevision, long revision) {}
    record Page(List<Configuration> items, String nextCursor) {}
    record Probe(String id, String connectorId, long configurationRevision, String state, String resultVersionId, long revision, String errorCode) {}
    Page list(ChatApi.Caller caller, String cursor, int limit);
    Configuration get(ChatApi.Caller caller, String id);
    Configuration create(ChatApi.Caller caller, String requestId, Input input);
    Configuration update(ChatApi.Caller caller, String id, long expected, String requestId, Input input);
    Probe test(ChatApi.Caller caller, String id, long expected, String requestId);
    Probe probe(ChatApi.Caller caller, String id);
}
