package com.agentplatform.app.files;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated @ConfigurationProperties("platform.workspace.files")
public record FileExecutionProperties(@NotBlank @Pattern(regexp="sha256:[a-f0-9]{64}") String helperImage,
        @NotBlank @Pattern(regexp="[a-f0-9]{64}") String policyHash) {}
