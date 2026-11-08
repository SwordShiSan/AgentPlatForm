package com.agentplatform.app.capability;

import com.agentplatform.domain.capability.CapabilityMaterializationRunnerPort;
import com.agentplatform.domain.chat.ChatRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Acceptance-only entry point; R6.2 does not register materialized capabilities as live tools. */
@Configuration(proxyBeanMethods=false)
@Profile("chat-worker")
@ConditionalOnProperty(name="platform.capabilities.enabled",havingValue="true")
public class CapabilityMaterializationWorkerConfiguration {
    @Bean CapabilityMaterializationControlService capabilityMaterializationControlService(ChatRepository chat,
            CapabilityBindingService bindings,SkillVersionService versions,CapabilityMaterializationRunnerPort runner) {
        return new CapabilityMaterializationControlService(chat,bindings,versions,runner);
    }
}
