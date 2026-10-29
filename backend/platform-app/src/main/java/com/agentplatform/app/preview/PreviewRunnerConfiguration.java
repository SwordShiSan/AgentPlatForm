package com.agentplatform.app.preview;

import com.agentplatform.api.files.PreviewRunnerApi;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.domain.preview.*;
import com.agentplatform.infrastructure.preview.*;
import com.agentplatform.infrastructure.workspace.*;
import java.nio.file.Path;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.*;
import org.springframework.context.annotation.*;
import org.springframework.validation.annotation.Validated;

@Configuration(proxyBeanMethods=false)
@Profile("workspace-runner")
@ConditionalOnProperty(name="platform.preview.enabled",havingValue="true")
@EnableConfigurationProperties({PreviewProperties.class,PreviewRunnerConfiguration.Properties.class})
public class PreviewRunnerConfiguration {
    @Validated @ConfigurationProperties("platform.preview.runner")
    public record Properties(@NotNull Path stagingRoot,@NotNull Path runtimeAttestation) {}
    @Bean(destroyMethod="close") @Profile("workspace-runner")
    PreviewRuntime previewRuntime(PreviewProperties properties,Properties runner,UUID runnerInstanceNonce){
        var policy=new PreviewRuntimePolicy(properties.imageId(),runner.stagingRoot(),runner.runtimeAttestation(),properties.bundle());
        if(!policy.hash().equals(properties.policyHash()))throw new IllegalArgumentException("Frozen preview policy mismatch");
        return new DockerPreviewRuntime(policy,runnerInstanceNonce);
    }
    @Bean(destroyMethod="close") @Profile("workspace-runner")
    PreviewRunnerApi previewRunnerApi(PreviewRepository repository,PreviewRuntime runtime,AssetStore assets,RunnerSignatures signatures,Properties properties){
        return new PreviewRunnerService(repository,runtime,assets,signatures,properties.stagingRoot());
    }
}
