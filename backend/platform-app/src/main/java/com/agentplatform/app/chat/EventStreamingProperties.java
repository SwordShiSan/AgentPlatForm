package com.agentplatform.app.chat;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("platform.chat.stream")
public record EventStreamingProperties(
        @DefaultValue("8") @Min(1) @Max(8) int maxConnections,
        @DefaultValue("25") @Min(1) @Max(25) int lifetimeSeconds,
        @DefaultValue("2000") @Min(100) @Max(2000) int heartbeatMillis,
        @DefaultValue("250") @Min(10) @Max(1000) int pollMillis,
        @DefaultValue("256") @Min(1) @Max(256) int pageEvents,
        @DefaultValue("1048576") @Min(4096) @Max(1048576) int bufferBytes) {}
