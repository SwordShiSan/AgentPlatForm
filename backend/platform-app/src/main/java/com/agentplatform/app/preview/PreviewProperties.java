package com.agentplatform.app.preview;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated @ConfigurationProperties("platform.preview")
public record PreviewProperties(@NotBlank @Pattern(regexp="sha256:[a-f0-9]{64}") String imageId,
        @NotBlank @Size(max=128) String bundle,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String policyHash) {}
