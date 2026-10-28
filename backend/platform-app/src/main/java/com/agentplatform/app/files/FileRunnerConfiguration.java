package com.agentplatform.app.files;

import com.agentplatform.api.files.FileRunnerApi;
import com.agentplatform.app.workspace.WorkspaceRunnerProperties;
import com.agentplatform.domain.files.*;
import com.agentplatform.infrastructure.adapter.repository.files.MyBatisFileRunnerRepository;
import com.agentplatform.infrastructure.dao.files.*;
import com.agentplatform.infrastructure.dao.workspace.*;
import com.agentplatform.infrastructure.workspace.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false) @Profile("workspace-runner")
@ConditionalOnProperty(name={"platform.workspace.enabled","platform.workspace.files.enabled"},havingValue="true")
public class FileRunnerConfiguration {
    @Bean FileRunnerRepository fileRunnerRepository(FileRunnerMapper runner,FileMapper files,WorkspaceMapper workspaces,
            EnvironmentPreparationMapper environments,EnvironmentExecutionMapper instances,FileTransferMapper transfers,FileCaptureMapper captures,TransactionTemplate transactions) {
        return new TransactionalFileRunnerRepository(new MyBatisFileRunnerRepository(runner,files,workspaces,environments,instances,transfers,captures),transactions);
    }
    @Bean(destroyMethod="close") FileRuntime fileRuntime(WorkspaceRunnerProperties workspace,FileExecutionProperties files,
            AuxiliaryContainerSlot auxiliaryContainerSlot,java.util.UUID runnerInstanceNonce) {
        var policy=new FileRuntimePolicy(files.helperImage(),workspace.helperImage(),workspace.workspaceRoot(),workspace.mountUuid(),workspace.quotaDevice());
        if(!policy.hash().equals(files.policyHash()))throw new IllegalStateException("Frozen file helper policy mismatch");
        return new DockerFileRuntime(policy,auxiliaryContainerSlot,runnerInstanceNonce);
    }
    @Bean FileRunnerApi fileRunnerApi(FileRunnerRepository repository,FileRuntime runtime,AssetStore assets,RunnerSignatures signatures) {
        return new FileRunnerService(repository,runtime,assets,signatures);
    }
    @Bean @ConditionalOnProperty(name="platform.workspace.tools.enabled",havingValue="true")
    com.agentplatform.api.files.FileCaptureRunnerApi fileCaptureRunnerApi(FileRunnerRepository repository,FileRuntime runtime,AssetStore assets,RunnerSignatures signatures) {
        return new FileCaptureRunnerService(repository,runtime,assets,signatures);
    }
}
