package com.agentplatform.app.persistence;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("platform.storage")
public record StorageProperties(@NotBlank String jdbcUrl, @NotBlank String user, @NotNull Path passwordFile) {}
