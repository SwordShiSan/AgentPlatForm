package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.ConfigurationResolver;
import com.agentplatform.infrastructure.adapter.repository.chat.MyBatisWorkspaceConfigurationRepository;
import com.agentplatform.infrastructure.dao.chat.WorkspaceConfigurationMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("chat-local")
@ConditionalOnProperty(name = "platform.personal-config.enabled", havingValue = "true")
@org.springframework.boot.context.properties.EnableConfigurationProperties(PersonalConfigurationProperties.class)
public class PersonalConfigurationWiring {
    @Bean EffectiveConfigService effectiveConfigService(WorkspaceConfigurationMapper mapper, TransactionTemplate transactions,
                                                         PersonalConfigurationProperties locked) {
        return new EffectiveConfigService(new MyBatisWorkspaceConfigurationRepository(mapper), transactions,
                new ConfigurationResolver.Layer(locked.modelId(), locked.credentialSource(), locked.planMode(), locked.permissionMode()));
    }
}
