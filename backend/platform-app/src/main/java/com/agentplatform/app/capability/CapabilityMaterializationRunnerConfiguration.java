package com.agentplatform.app.capability;

import com.agentplatform.api.capability.CapabilityMaterializationRunnerApi;
import com.agentplatform.domain.capability.CapabilityMaterializationRepository;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.domain.workspace.WorkspaceRepository;
import com.agentplatform.infrastructure.adapter.repository.capability.MyBatisCapabilityMaterializationRepository;
import com.agentplatform.infrastructure.dao.capability.CapabilityMaterializationMapper;
import com.agentplatform.infrastructure.workspace.DockerSandboxRuntime;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import com.agentplatform.infrastructure.workspace.SandboxPolicy;
import com.agentplatform.app.workspace.WorkspaceRunnerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;

@Configuration(proxyBeanMethods=false)
@Profile("workspace-runner")
@ConditionalOnProperty(name={"platform.capabilities.enabled","platform.workspace.files.enabled"},havingValue="true")
public class CapabilityMaterializationRunnerConfiguration {
    @Bean CapabilityMaterializationRepository capabilityMaterializationRepository(CapabilityMaterializationMapper mapper,
            TransactionTemplate transactions) {
        return new TransactionalCapabilityMaterializationRepository(mapper,transactions);
    }

    @Bean CapabilityMaterializationRunnerApi capabilityMaterializationRunnerApi(CapabilityMaterializationRepository repository,
            RunnerSignatures signatures,WorkspaceRepository workspaces,CapabilityBindingService bindings,
            SkillVersionService versions,AssetStore assets,DockerSandboxRuntime runtime,WorkspaceRunnerProperties runner,
            SandboxPolicy policy) {
        return new CapabilityMaterializationRunnerService(repository,signatures,workspaces,bindings,versions,assets,runtime,
                Path.of(runner.workspaceRoot()),policy.hash());
    }
}
