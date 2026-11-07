package com.agentplatform.app.capability;

import com.agentplatform.api.capability.McpConnectorApi;
import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.infrastructure.adapter.repository.capability.MyBatisMcpConnectorRepository;
import com.agentplatform.infrastructure.dao.capability.McpConnectorMapper;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("workspace-api")
@ConditionalOnProperty(name = {"platform.capabilities.enabled", "platform.mcp.enabled"}, havingValue = "true")
public class McpConnectorConfiguration {
    @Bean McpConnectorApi mcpConnectorApi(McpConnectorMapper mapper, TransactionTemplate transactions,
                                          @Value("${platform.mcp.policy-hash}") String policyHash) {
        var service = new McpConnectorService(new MyBatisMcpConnectorRepository(mapper), transactions, policyHash);
        return new McpConnectorApi() {
            @Override public Page list(ChatApi.Caller caller, String cursor, int limit) {
                var page = service.list(actor(caller), cursor == null ? null : uuid(cursor), limit);
                return new Page(page.items().stream().map(McpConnectorConfiguration::view).toList(),
                        page.nextCursor() == null ? null : page.nextCursor().toString());
            }
            @Override public Configuration get(ChatApi.Caller caller, String id) { return view(service.get(actor(caller), uuid(id))); }
            @Override public Configuration create(ChatApi.Caller caller, String requestId, Input input) {
                return view(service.create(actor(caller), uuid(requestId), input(input), com.agentplatform.types.RequestTrace.current()));
            }
            @Override public Configuration update(ChatApi.Caller caller, String id, long expected, String requestId, Input input) {
                return view(service.update(actor(caller), uuid(id), expected, uuid(requestId), input(input), com.agentplatform.types.RequestTrace.current()));
            }
            @Override public Probe test(ChatApi.Caller caller, String id, long expected, String requestId) {
                return view(service.enqueue(actor(caller), uuid(id), expected, uuid(requestId), com.agentplatform.types.RequestTrace.current()));
            }
            @Override public Probe probe(ChatApi.Caller caller, String id) { return view(service.probe(actor(caller), uuid(id))); }
        };
    }
    private static McpConnectorService.Input input(McpConnectorApi.Input value) {
        if (value == null) throw new FileFailure(400, "MCP_CONFIGURATION_INVALID");
        return new McpConnectorService.Input(value.endpoint(), value.authKind(), value.apiKeyHeader(),
                value.credentialId() == null ? null : uuid(value.credentialId()), value.credentialRevision());
    }
    private static McpConnectorApi.Configuration view(com.agentplatform.domain.capability.McpConnectorRepository.Configuration value) {
        return new McpConnectorApi.Configuration(value.id().toString(), value.endpoint(), value.authKind(), value.apiKeyHeader(),
                value.credentialId() == null ? null : value.credentialId().toString(), value.credentialRevision(), value.revision());
    }
    private static McpConnectorApi.Probe view(com.agentplatform.domain.capability.McpConnectorRepository.Probe value) {
        return new McpConnectorApi.Probe(value.id().toString(), value.connectorId().toString(), value.configurationRevision(), value.state(),
                value.resultVersionId() == null ? null : value.resultVersionId().toString(), value.revision());
    }
    private static Actor actor(ChatApi.Caller caller) {
        if (caller == null) throw new FileFailure(401, "AUTHENTICATION_REQUIRED");
        return new Actor(uuid(caller.userId()), uuid(caller.spaceId()));
    }
    private static UUID uuid(String value) { try { return UUID.fromString(value); } catch (RuntimeException invalid) { throw new FileFailure(400, "INVALID_UUID"); } }
}
