package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.RunnerControlPort;
import com.agentplatform.infrastructure.workspace.*;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false) @Profile("workspace-worker")
@EnableConfigurationProperties(WorkspaceControlProperties.class)
public class WorkspaceControlConfiguration {
    @Bean RunnerSignatures controlSignatures(WorkspaceControlProperties properties) {
        return new RunnerSignatures(WorkspaceKeys.read(properties.privateKeyFile(),properties.keyId(),true),
                WorkspaceKeys.read(properties.runnerPublicKeyFile(),properties.runnerKeyId(),false),"platform-control","platform-runner",Clock.systemUTC());
    }
    @Bean(destroyMethod="close") SignedRunnerHttpClient runnerControlPort(WorkspaceControlProperties properties,RunnerSignatures signatures) {
        return new SignedRunnerHttpClient(properties.runnerBase(),signatures);
    }
}
