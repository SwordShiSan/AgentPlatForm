package com.agentplatform.app.workspace;

import java.nio.file.Path;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated @ConfigurationProperties("platform.workspace.environment.runtime")
public record EnvironmentRuntimeProperties(@NotNull Path attestationFile) {}
