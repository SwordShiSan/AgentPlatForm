package com.agentplatform.app.capability;

import com.agentplatform.api.capability.ConnectorCredentialApi;
import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.ConnectorCredentialRepository.Metadata;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.infrastructure.adapter.repository.capability.MyBatisConnectorCredentialRepository;
import com.agentplatform.infrastructure.dao.capability.ConnectorCredentialMapper;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("workspace-api")
@ConditionalOnProperty(name = {"platform.capabilities.enabled", "platform.mcp.enabled"}, havingValue = "true")
public class ConnectorCredentialConfiguration {
    @Bean ConnectorCredentialApi connectorCredentialApi(ConnectorCredentialMapper mapper, TransactionTemplate transactions,
            @Value("${platform.mcp.master-key-file}") String mcpFile,
            @Value("${platform.chat.master-key-file}") String modelFile) throws java.io.IOException {
        byte[] mcp = IndependentMcpKeyLoader.load(mcpFile, modelFile);
        try {
            var service = new ConnectorCredentialService(new MyBatisConnectorCredentialRepository(mapper), transactions, mcp);
            return new ConnectorCredentialApi() {
                @Override public Page list(ChatApi.Caller caller, String cursor, int limit) {
                    var page = service.list(actor(caller), cursor == null ? null : uuid(cursor), limit);
                    return new Page(page.items().stream().map(ConnectorCredentialConfiguration::view).toList(),
                            page.nextCursor() == null ? null : page.nextCursor().toString());
                }
                @Override public ConnectorCredentialApi.Metadata create(ChatApi.Caller caller, String requestId, String secret) {
                    return view(service.create(actor(caller), uuid(requestId), secret, com.agentplatform.types.RequestTrace.current()));
                }
                @Override public ConnectorCredentialApi.Metadata replace(ChatApi.Caller caller, String id, long expected, String requestId, String secret) {
                    return view(service.replace(actor(caller), uuid(id), expected, uuid(requestId), secret, com.agentplatform.types.RequestTrace.current()));
                }
                @Override public ConnectorCredentialApi.Metadata revoke(ChatApi.Caller caller, String id, long expected, String requestId) {
                    return view(service.revoke(actor(caller), uuid(id), expected, uuid(requestId), com.agentplatform.types.RequestTrace.current()));
                }
            };
        } finally { Arrays.fill(mcp, (byte) 0); }
    }
    private static ConnectorCredentialApi.Metadata view(Metadata value) {
        return new ConnectorCredentialApi.Metadata(value.id().toString(), value.revision(), value.state(), value.createdAt().toString());
    }
    private static Actor actor(ChatApi.Caller caller) {
        if (caller == null) throw new FileFailure(401, "AUTHENTICATION_REQUIRED");
        return new Actor(uuid(caller.userId()), uuid(caller.spaceId()));
    }
    private static UUID uuid(String value) {
        try { return UUID.fromString(value); } catch (RuntimeException invalid) { throw new FileFailure(400, "INVALID_UUID"); }
    }
}
