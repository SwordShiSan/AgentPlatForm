package com.agentplatform.app.execution.harness;

import com.agentplatform.domain.harness.HarnessPorts.*;
import com.agentplatform.infrastructure.harness.agentscope.AgentScopeRuntimeFactory;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Disabled shell stays inert. Enabling requires all explicit adapters and an operator path. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.runtime.agentscope.enabled", havingValue = "true")
public class HarnessRuntimeConfiguration {
    @Bean
    LoopDriver loopDriver(ModelGateway model, PolicyPort policy, ToolExecutionPort tools, StateStorage storage,
                          RuntimeStatePort manifests, ToolCatalogPort catalog,
                          @Value("${platform.runtime.agentscope.workspace-root}") String workspaceRoot) {
        return new AgentScopeRuntimeFactory(model, policy, tools, storage, manifests, catalog.definitions(), Path.of(workspaceRoot));
    }
}
