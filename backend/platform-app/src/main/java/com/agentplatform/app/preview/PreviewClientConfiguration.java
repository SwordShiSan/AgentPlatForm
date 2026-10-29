package com.agentplatform.app.preview;

import com.agentplatform.app.workspace.WorkspaceControlProperties;
import com.agentplatform.domain.preview.PreviewRunnerPort;
import com.agentplatform.infrastructure.workspace.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false) @Profile("workspace-worker")
@ConditionalOnProperty(name="platform.preview.enabled",havingValue="true")
public class PreviewClientConfiguration {
    @Bean(destroyMethod="close")
    PreviewRunnerPort previewRunnerPort(WorkspaceControlProperties control,RunnerSignatures signatures){
        return new SignedPreviewRunnerClient(control.runnerBase(),signatures);
    }
}
