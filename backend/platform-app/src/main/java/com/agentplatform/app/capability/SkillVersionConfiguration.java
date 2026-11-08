package com.agentplatform.app.capability;

import com.agentplatform.api.capability.SkillVersionApi;
import com.agentplatform.api.capability.SkillCatalogApi;
import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.infrastructure.adapter.repository.capability.MyBatisSkillVersionRepository;
import com.agentplatform.infrastructure.dao.capability.SkillVersionMapper;
import com.agentplatform.infrastructure.dao.capability.SkillCatalogMapper;
import com.agentplatform.infrastructure.adapter.repository.capability.MyBatisSkillCatalogRepository;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@Profile({"workspace-api", "chat-worker", "workspace-runner"})
@ConditionalOnProperty(name = "platform.capabilities.enabled", havingValue = "true")
public class SkillVersionConfiguration {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Bean SkillVersionService skillVersionService(SkillVersionMapper mapper, TransactionTemplate transactions) {
        return new SkillVersionService(new MyBatisSkillVersionRepository(mapper), transactions);
    }
    @Bean @Profile("workspace-api") SkillCatalogApi skillCatalogApi(SkillCatalogMapper mapper, TransactionTemplate transactions) {
        return new SkillCatalogService(new MyBatisSkillCatalogRepository(mapper), transactions);
    }
    @Bean @Profile("workspace-api") SkillVersionApi skillVersionApi(SkillVersionService service) {
        return new SkillVersionApi() {
            @Override public View get(com.agentplatform.api.chat.ChatApi.Caller caller, String capabilityId, String versionId) {
                return view(service.get(actor(caller), uuid(capabilityId), uuid(versionId)));
            }
            @Override public View change(com.agentplatform.api.chat.ChatApi.Caller caller, String capabilityId, String versionId,
                                         String action, long expected, String requestId) {
                return view(service.change(actor(caller), uuid(capabilityId), uuid(versionId), action, expected,
                        uuid(requestId), com.agentplatform.types.RequestTrace.current()));
            }
        };
    }
    private static SkillVersionApi.View view(com.agentplatform.domain.capability.SkillVersionRepository.View source) {
        return new SkillVersionApi.View(source.capabilityId().toString(), source.versionId().toString(), source.state(), source.revision(),
                source.name(), source.description(), Arrays.asList(JSON.readValue(source.manifestJson(), SkillVersionApi.Resource[].class)),
                Arrays.asList(JSON.readValue(source.dependenciesJson(), Object[].class)), source.assetAvailable());
    }
    private static Actor actor(com.agentplatform.api.chat.ChatApi.Caller caller) {
        if (caller == null) throw new FileFailure(401, "AUTHENTICATION_REQUIRED");
        return new Actor(uuid(caller.userId()), uuid(caller.spaceId()));
    }
    private static UUID uuid(String value) {
        try { return UUID.fromString(value); } catch (RuntimeException invalid) { throw new FileFailure(400, "INVALID_UUID"); }
    }
}
