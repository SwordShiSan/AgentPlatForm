package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.ConfigurationResolver.CredentialSource;
import com.agentplatform.types.TaskPermissionData.Mode;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional deployment locks; absent fields are not locked. */
@ConfigurationProperties(prefix="platform.personal-config.locked")
public record PersonalConfigurationProperties(UUID modelId, CredentialSource credentialSource,
                                              Boolean planMode, Mode permissionMode) {}
