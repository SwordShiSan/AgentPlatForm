package com.agentplatform.api.chat;

/** Workspace-specific overrides; null values mean inheritance. */
public interface WorkspaceSettingsApi {
    record Values(String defaultModelId, String defaultCredentialSource, Boolean defaultPlanEnabled, String defaultApprovalMode) {}
    record Settings(long version, Values overrides) {}
    record Patch(boolean modelPresent, String modelId, boolean credentialPresent, String credentialSource,
                 boolean planPresent, Boolean planMode, boolean permissionPresent, String permissionMode) {}
    Settings read(ChatApi.Caller caller, String workspaceId);
    Settings update(ChatApi.Caller caller, String workspaceId, long expected, Patch patch);
}
