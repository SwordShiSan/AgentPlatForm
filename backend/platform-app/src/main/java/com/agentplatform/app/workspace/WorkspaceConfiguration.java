package com.agentplatform.app.workspace;

import com.agentplatform.api.workspace.WorkspaceApi;
import com.agentplatform.domain.workspace.WorkspaceRepository;
import com.agentplatform.infrastructure.adapter.repository.workspace.MyBatisWorkspaceRepository;
import com.agentplatform.infrastructure.dao.workspace.WorkspaceMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@Profile({"workspace-api","workspace-worker","workspace-runner"})
@ConditionalOnProperty(name="platform.workspace.enabled",havingValue="true")
public class WorkspaceConfiguration {
    @Bean WorkspaceFixtureBarrier workspaceFixtureBarrier(org.springframework.core.env.Environment environment,
            @org.springframework.beans.factory.annotation.Value("${platform.workspace.fixture.point:}")String point,
            @org.springframework.beans.factory.annotation.Value("${platform.workspace.fixture.stage:}")String stage) {
        return WorkspaceFixtureBarrier.configured(point,stage,environment.acceptsProfiles(org.springframework.core.env.Profiles.of("workspace-fixture")),
                environment.acceptsProfiles(org.springframework.core.env.Profiles.of("r1-http | cloud-api | cloud-worker | cloud-runner | production")));
    }
    @Bean WorkspaceRepository workspaceRepository(WorkspaceMapper mapper,TransactionTemplate transactions,WorkspaceFixtureBarrier barrier) {
        return new TransactionalWorkspaceRepository(new MyBatisWorkspaceRepository(mapper),transactions,barrier);
    }
    @Bean @Profile("workspace-api") WorkspaceApi workspaceApi(WorkspaceRepository repository) {
        return new WorkspaceApplicationService(repository);
    }
}
