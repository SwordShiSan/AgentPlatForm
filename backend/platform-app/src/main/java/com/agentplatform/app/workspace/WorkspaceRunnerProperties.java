package com.agentplatform.app.workspace;

import java.nio.file.Path;
import java.util.UUID;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated @ConfigurationProperties("platform.workspace.runner")
public record WorkspaceRunnerProperties(@NotNull Path privateKeyFile,@NotNull Path controlPublicKeyFile,
        @NotBlank @Pattern(regexp="[a-z][a-z0-9-]{0,63}") String keyId,
        @NotBlank @Pattern(regexp="[a-z][a-z0-9-]{0,63}") String controlKeyId,
        @NotBlank @Pattern(regexp="sha256:[a-f0-9]{64}") String helperImage,
        @NotBlank String workspaceRoot,@NotNull UUID mountUuid,
        @Pattern(regexp="|/dev/loop[0-9]{1,5}") String quotaDevice) {}
