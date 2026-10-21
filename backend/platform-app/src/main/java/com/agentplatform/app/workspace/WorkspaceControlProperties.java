package com.agentplatform.app.workspace;

import java.net.URI;
import java.nio.file.Path;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated @ConfigurationProperties("platform.workspace.control")
public record WorkspaceControlProperties(@NotNull URI runnerBase,@NotNull Path privateKeyFile,
        @NotNull Path runnerPublicKeyFile,@Pattern(regexp="[a-z][a-z0-9-]{0,63}") @NotBlank String keyId,
        @Pattern(regexp="[a-z][a-z0-9-]{0,63}") @NotBlank String runnerKeyId) {}
