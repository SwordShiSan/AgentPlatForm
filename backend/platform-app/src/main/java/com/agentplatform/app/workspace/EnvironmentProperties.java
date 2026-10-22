package com.agentplatform.app.workspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated @ConfigurationProperties("platform.workspace.environment")
public record EnvironmentProperties(@NotBlank @Pattern(regexp="sha256:[0-9a-f]{64}") String imageId,
                                    @NotBlank @Pattern(regexp="[0-9a-f]{64}") String policyHash) {}
