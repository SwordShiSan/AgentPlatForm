package com.agentplatform.app.capability;

import com.agentplatform.infrastructure.adapter.repository.capability.MyBatisCapabilityBindingRepository;
import com.agentplatform.infrastructure.dao.capability.CapabilityBindingMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("chat-local")
@ConditionalOnProperty(name = "platform.capabilities.enabled", havingValue = "true")
public class CapabilityConfiguration {
    @Bean CapabilityBindingService capabilityBindingService(CapabilityBindingMapper mapper, TransactionTemplate transactions) {
        return new CapabilityBindingService(new MyBatisCapabilityBindingRepository(mapper), transactions);
    }
    @Bean com.agentplatform.api.capability.CapabilityBindingsApi capabilityBindingsApi(CapabilityBindingService service) {
        return new com.agentplatform.api.capability.CapabilityBindingsApi() {
            @Override public Bindings read(com.agentplatform.api.chat.ChatApi.Caller caller, String workspaceId) {
                return service.read(caller, workspaceId);
            }
            @Override public Bindings replace(com.agentplatform.api.chat.ChatApi.Caller caller, String workspaceId,
                                               long expected, Update update, String requestId) {
                return service.replace(caller, workspaceId, expected, update, requestId);
            }
            @Override public Effective effective(com.agentplatform.api.chat.ChatApi.Caller caller, String workspaceId) {
                return service.effective(caller, workspaceId);
            }
        };
    }
}
