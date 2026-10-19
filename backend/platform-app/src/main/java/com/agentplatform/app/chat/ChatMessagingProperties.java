package com.agentplatform.app.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("platform.chat")
public record ChatMessagingProperties(@NotBlank String mqHost, @NotBlank String mqUser, @NotNull Path mqPasswordFile) {}
