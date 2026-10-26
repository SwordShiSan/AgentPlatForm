package com.agentplatform.api.chat;

import com.agentplatform.types.TaskPermissionData.*;

public interface TaskPermissionApi {
    View permissions(ChatApi.Caller caller, String task);
    Scopes permissionScopes(ChatApi.Caller caller, String task);
    Scopes permissionScopes(ChatApi.Caller caller, String task, String cursor);
    View permissionMode(ChatApi.Caller caller, String task, String key, long expected, Mode mode);
    View grant(ChatApi.Caller caller, String task, String key, long expected, CreateGrant grant);
    View revokeGrant(ChatApi.Caller caller, String task, String grant, String key, long expected);
}
