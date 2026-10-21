package com.agentplatform.app.workspace;

import com.agentplatform.api.workspace.RunnerControlApi;
import com.agentplatform.domain.workspace.*;
import com.agentplatform.infrastructure.workspace.*;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Configuration(proxyBeanMethods=false) @Profile("workspace-runner")
@EnableConfigurationProperties(WorkspaceRunnerProperties.class)
public class WorkspaceRunnerConfiguration {
    @Bean RunnerSignatures runnerSignatures(WorkspaceRunnerProperties properties) {
        return new RunnerSignatures(WorkspaceKeys.read(properties.privateKeyFile(),properties.keyId(),true),
                WorkspaceKeys.read(properties.controlPublicKeyFile(),properties.controlKeyId(),false),"platform-runner","platform-control",Clock.systemUTC());
    }
    @Bean JwtDecoder runnerJwtDecoder(RunnerSignatures signatures) {return signatures.resourceServerDecoder();}
    @Bean(destroyMethod="close") DockerWorkspaceAllocator workspaceAllocator(WorkspaceRunnerProperties properties) {
        return new DockerWorkspaceAllocator(properties.helperImage(),properties.workspaceRoot(),properties.mountUuid());
    }
    @Bean RunnerControlApi runnerControlApi(WorkspaceRepository repository,RunnerSignatures signatures,WorkspaceAllocator allocator,WorkspaceFixtureBarrier barrier) {
        return new RunnerControlService(repository,signatures,allocator,barrier);
    }
}
