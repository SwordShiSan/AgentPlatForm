package com.agentplatform.app.capability;

import com.agentplatform.domain.capability.ConnectorCredentialRepository;
import com.agentplatform.domain.capability.McpVersionRepository;
import com.agentplatform.domain.chat.ChatRepository;
import com.agentplatform.infrastructure.adapter.repository.capability.MyBatisConnectorCredentialRepository;
import com.agentplatform.infrastructure.adapter.repository.capability.MyBatisMcpVersionRepository;
import com.agentplatform.infrastructure.chat.CredentialCipher;
import com.agentplatform.infrastructure.dao.capability.ConnectorCredentialMapper;
import com.agentplatform.infrastructure.dao.capability.McpVersionMapper;
import com.agentplatform.infrastructure.capability.McpDiscoveryProcess;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionTemplate;

/** Worker-only adapter for protected acceptance; it is not registered in the production tool catalog. */
@Configuration(proxyBeanMethods=false)
@Profile("workspace-worker")
@ConditionalOnProperty(name={"platform.capabilities.enabled","platform.mcp.enabled"},havingValue="true")
public class McpProtectedCallConfiguration {
    @Bean ProtectedMcpCallService protectedMcpCallService(ChatRepository chat,CapabilityBindingService bindings,
            McpVersionMapper versionMapper,ConnectorCredentialMapper credentialMapper,McpDiscoveryProcess process,
            TransactionTemplate transactions,@Value("${platform.mcp.policy-hash}") String policyHash,
            @Value("${platform.mcp.master-key-file}") String mcpFile,
            @Value("${platform.chat.master-key-file}") String modelFile) throws java.io.IOException {
        byte[] key=IndependentMcpKeyLoader.load(mcpFile,modelFile);
        try {
            McpVersionRepository versions=new MyBatisMcpVersionRepository(versionMapper);
            ConnectorCredentialRepository credentials=new MyBatisConnectorCredentialRepository(credentialMapper);
            return new ProtectedMcpCallService(chat,bindings,versions,credentials,new CredentialCipher(key),process,transactions,policyHash);
        } finally { Arrays.fill(key,(byte)0); }
    }
}
