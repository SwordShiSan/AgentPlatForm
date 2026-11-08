package com.agentplatform.app.workspace;

import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.validation.annotation.Validated;

/** Narrow runner identity for isolated renderer/extraction acceptance; it does not assemble workspace control APIs. */
@Configuration(proxyBeanMethods=false)
@Profile("preview-runner")
@EnableConfigurationProperties(PreviewRunnerIdentityConfiguration.Properties.class)
public class PreviewRunnerIdentityConfiguration {
    @Validated
    @ConfigurationProperties("platform.preview.runner.identity")
    public record Properties(@NotNull Path privateKeyFile, @NotNull Path controlPublicKeyFile,
            @NotBlank @Pattern(regexp="[a-z][a-z0-9-]{0,63}") String keyId,
            @NotBlank @Pattern(regexp="[a-z][a-z0-9-]{0,63}") String controlKeyId) {}

    @Bean UUID runnerInstanceNonce() { return UUID.randomUUID(); }

    @Bean RunnerSignatures runnerSignatures(Properties properties) {
        return new RunnerSignatures(WorkspaceKeys.read(properties.privateKeyFile(),properties.keyId(),true),
                WorkspaceKeys.read(properties.controlPublicKeyFile(),properties.controlKeyId(),false),
                "platform-runner","platform-control",Clock.systemUTC());
    }

    @Bean JwtDecoder runnerJwtDecoder(RunnerSignatures signatures) { return signatures.resourceServerDecoder(); }
}
