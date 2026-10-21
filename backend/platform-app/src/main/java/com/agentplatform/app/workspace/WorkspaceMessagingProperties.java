package com.agentplatform.app.workspace;

import java.nio.file.Path;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated @ConfigurationProperties("platform.workspace.messaging")
public record WorkspaceMessagingProperties(@NotBlank String host,@Min(1) @Max(65535) int port,
        @NotBlank String user,@NotNull Path passwordFile) {}
