package com.agentplatform.domain.chat;

import com.agentplatform.types.TaskPermissionData.Mode;
import java.util.UUID;

/** Presence-aware options for an effective configuration preview. */
public record ConfigurationQuery(UUID workspaceId, UUID modelId, ConfigurationResolver.CredentialSource credentialSource,
                                 Boolean planMode, Mode permissionMode) {}
