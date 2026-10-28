package com.agentplatform.app.workspace;

import com.agentplatform.api.workspace.*;
import com.agentplatform.domain.workspace.*;
import com.agentplatform.infrastructure.adapter.repository.workspace.*;
import com.agentplatform.infrastructure.dao.workspace.*;
import com.agentplatform.infrastructure.workspace.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false) @Profile({"workspace-api","workspace-worker","workspace-runner"})
@ConditionalOnProperty(name={"platform.workspace.enabled","platform.workspace.environment.enabled"},havingValue="true")
@EnableConfigurationProperties(EnvironmentProperties.class)
public class EnvironmentConfiguration {
    @Bean EnvironmentExecutionRepository environmentExecutionRepository(EnvironmentExecutionMapper execution,EnvironmentPreparationMapper preparation,
            WorkspaceMapper workspaces,TransactionTemplate transactions,WorkspaceFixtureBarrier barrier,
            @org.springframework.beans.factory.annotation.Value("${platform.workspace.tools.enabled:false}")boolean tools) {
        return new TransactionalEnvironmentExecutionRepository(new MyBatisEnvironmentExecutionRepository(execution,preparation,workspaces,tools),transactions,barrier);
    }
    @Bean @Profile({"workspace-worker","workspace-runner"}) EnvironmentUseRepository environmentUseRepository(EnvironmentUseMapper uses,
            EnvironmentPreparationMapper preparation,EnvironmentExecutionMapper execution,WorkspaceMapper workspaces,FileHelperRepository fileHelpers,TransactionTemplate transactions) {
        return new TransactionalEnvironmentUseRepository(new MyBatisEnvironmentUseRepository(uses,preparation,execution,workspaces,fileHelpers),transactions);
    }
    @Bean @Profile({"workspace-worker","workspace-runner"}) FileHelperRepository fileHelperRepository(FileHelperMapper helpers,EnvironmentUseMapper uses,
            EnvironmentPreparationMapper preparation,EnvironmentExecutionMapper execution,WorkspaceMapper workspaces,TransactionTemplate transactions) {
        return new TransactionalFileHelperRepository(new MyBatisFileHelperRepository(helpers,uses,preparation,execution,workspaces),transactions);
    }
    @Bean @Profile({"workspace-api","workspace-worker"}) EnvironmentLifecycleRepository environmentLifecycleRepository(EnvironmentLifecycleMapper lifecycle,EnvironmentPreparationMapper preparation,
            WorkspaceMapper workspaces,TransactionTemplate transactions,EnvironmentProperties properties) {
        return new TransactionalEnvironmentLifecycleRepository(new MyBatisEnvironmentLifecycleRepository(lifecycle,preparation,workspaces,properties.imageId(),properties.policyHash()),transactions);
    }
    @Bean @Profile("workspace-api") WorkspaceEnvironmentApi workspaceEnvironmentApi(EnvironmentLifecycleRepository repository,Environment selected) {
        if(!selected.acceptsProfiles(Profiles.of("identity-local")))throw new IllegalStateException("Workspace environment API requires the existing identity boundary");
        return new WorkspaceLifecycleService(repository);
    }
    @Bean @Profile({"workspace-api","workspace-worker"}) EnvironmentMaintenanceRepository environmentMaintenanceRepository(EnvironmentMaintenanceMapper maintenance,
            EnvironmentPreparationMapper preparation,EnvironmentExecutionMapper execution,WorkspaceMapper workspaces,TransactionTemplate transactions) {
        return new TransactionalEnvironmentMaintenanceRepository(new MyBatisEnvironmentMaintenanceRepository(maintenance,preparation,execution,workspaces),transactions);
    }
    @Configuration(proxyBeanMethods=false) @Profile("workspace-runner")
    @ConditionalOnProperty(name={"platform.workspace.enabled","platform.workspace.environment.enabled"},havingValue="true")
    @EnableConfigurationProperties({EnvironmentProperties.class,EnvironmentRuntimeProperties.class})
    static class RuntimeConfiguration {
        @Bean SandboxPolicy sandboxPolicy(WorkspaceRunnerProperties runner,EnvironmentProperties environment,EnvironmentRuntimeProperties runtime) {
            var policy=new SandboxPolicy(environment.imageId(),runner.helperImage(),runner.workspaceRoot(),runner.mountUuid(),runner.quotaDevice(),runtime.attestationFile());
            if(!environment.policyHash().equals(policy.hash()))throw new IllegalStateException("Frozen environment policy mismatch");
            return policy;
        }
        @Bean(destroyMethod="close") SandboxRuntime sandboxRuntime(SandboxPolicy policy,WorkspaceFixtureBarrier barrier,
                java.util.UUID runnerInstanceNonce,AuxiliaryContainerSlot auxiliaryContainerSlot) {
            return new DockerSandboxRuntime(policy,barrier,runnerInstanceNonce,auxiliaryContainerSlot);
        }
        @Bean EnvironmentRunnerApi environmentRunnerApi(EnvironmentExecutionRepository repository,RunnerSignatures signatures,SandboxRuntime runtime,EnvironmentProperties properties,WorkspaceFixtureBarrier barrier) {
            return new EnvironmentRunnerService(repository,signatures,runtime,properties.imageId(),properties.policyHash(),runtime.runnerInstance(),barrier);
        }
        @Bean EnvironmentUseRunnerApi environmentUseRunnerApi(EnvironmentUseRepository repository,EnvironmentExecutionRepository execution,
                RunnerSignatures signatures,SandboxRuntime runtime,FileHelperRepository fileHelpers,WorkspaceFixtureBarrier barrier) {
            var instance=runtime.runnerInstance();execution.registerRunner(instance);
            return new EnvironmentUseRunnerService(repository,signatures,runtime,instance,barrier,fileHelpers);
        }
        @Bean @ConditionalOnProperty(name="platform.workspace.tools.enabled",havingValue="true")
        WorkspaceShellRunnerApi workspaceShellRunnerApi(EnvironmentUseRepository repository,EnvironmentExecutionRepository execution,
                RunnerSignatures signatures,SandboxRuntime runtime,WorkspaceFixtureBarrier barrier) {
            var instance=runtime.runnerInstance();execution.registerRunner(instance);
            return new WorkspaceShellRunnerService(repository,signatures,runtime,instance,barrier);
        }
    }
}
