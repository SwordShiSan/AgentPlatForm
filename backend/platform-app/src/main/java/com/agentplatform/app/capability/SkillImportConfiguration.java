package com.agentplatform.app.capability;

import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.infrastructure.dao.capability.SkillImportMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("workspace-api")
@ConditionalOnProperty(name = "platform.capabilities.enabled", havingValue = "true")
public class SkillImportConfiguration {
    @Bean SkillImportService skillImportService(SkillImportMapper mapper, TransactionTemplate transactions, AssetStore assets) {
        return new SkillImportService(new TransactionalSkillImportRepository(mapper, transactions), assets);
    }
}
