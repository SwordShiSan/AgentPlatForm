package com.agentplatform.api.capability;

import com.agentplatform.api.chat.ChatApi;
import java.util.List;

public interface McpConnectorApi {
    record Input(String endpoint, String authKind, String apiKeyHeader, String credentialId, Long credentialRevision) {}
    record Configuration(String id, String endpoint, String authKind, String apiKeyHeader, String credentialId, Long credentialRevision, long revision) {}
    record Page(List<Configuration> items, String nextCursor) {}
    record Probe(String id, String connectorId, long configurationRevision, String state, String resultVersionId, long revision, String errorCode) {}
    record Version(String connectorId, String versionId, long versionNumber, String state, long revision, String endpoint,
                   String authKind, String credentialId, Long credentialRevision, String policyHash, int toolCount, long sourceConfigurationRevision) {}
    record VersionPage(List<Version> items, Long nextCursor) {}
    record Tool(String name, String description, String schemaJson, String schemaSha256) {}
    record Activation(long revision, String enabledVersionId) {}
    record VersionDetail(Version version, List<Tool> tools, Activation activation, boolean eligible) {}
    Page list(ChatApi.Caller caller, String cursor, int limit);
    Configuration get(ChatApi.Caller caller, String id);
    Configuration byRequest(ChatApi.Caller caller, String requestId);
    Configuration create(ChatApi.Caller caller, String requestId, Input input);
    Configuration update(ChatApi.Caller caller, String id, long expected, String requestId, Input input);
    Probe test(ChatApi.Caller caller, String id, long expected, String requestId);
    Probe probe(ChatApi.Caller caller, String id);
    Probe probeByRequest(ChatApi.Caller caller, String requestId);
    VersionPage versions(ChatApi.Caller caller, String id, Long cursor, int limit);
    VersionDetail version(ChatApi.Caller caller, String id, String versionId);
    Activation activation(ChatApi.Caller caller, String id);
    Activation enable(ChatApi.Caller caller, String id, String versionId, long expected, String requestId);
    Activation disable(ChatApi.Caller caller, String id, long expected, String requestId);
}
