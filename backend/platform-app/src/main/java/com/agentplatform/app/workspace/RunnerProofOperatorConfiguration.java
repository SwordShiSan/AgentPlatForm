package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.RunnerStopProofRepository;
import com.agentplatform.infrastructure.adapter.repository.workspace.MyBatisRunnerStopProofRepository;
import com.agentplatform.infrastructure.dao.workspace.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false) @Profile("workspace-environment-ops")
@ConditionalOnProperty(name="platform.workspace.environment.ops-enabled",havingValue="true")
public class RunnerProofOperatorConfiguration {
    @Bean RunnerStopProofRepository runnerStopProofRepository(RunnerStopProofMapper mapper,EnvironmentPreparationMapper environments,
            TransactionTemplate transactions,Environment selected) {
        if(selected.acceptsProfiles(Profiles.of("workspace-api | workspace-worker | workspace-runner | workspace-environment-fixture | identity-local | chat-local | chat-worker | production | cloud-api | cloud-worker")))
            throw new IllegalStateException("Workspace host proof operator must be a standalone process");
        return new TransactionalRunnerStopProofRepository(new MyBatisRunnerStopProofRepository(mapper,environments),transactions);
    }
}
