package com.agentplatform.domain.chat;

import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.types.TaskPermissionData.*;
import com.agentplatform.types.harness.HarnessData;
import java.util.UUID;

public interface PermissionLedger {
    View permissions(Actor actor, UUID task);
    Scopes permissionScopes(Actor actor, UUID task);
    Scopes permissionScopes(Actor actor, UUID task, String cursor);
    View permissionMode(Actor actor, UUID task, UUID command, long expected, Mode mode);
    View grant(Actor actor, UUID task, UUID command, long expected, CreateGrant request);
    View revokeGrant(Actor actor, UUID task, UUID grant, UUID command, long expected);
    HarnessData.Decision operationDecision(Lease lease, HarnessData.Call call);
    ChatRepository.First firstAuthorized(Actor actor, UUID key, String title, UUID model, String source, String text, boolean plan, UUID workspace, Mode mode);
    Task submitAuthorized(Actor actor, UUID conversation, UUID key, UUID model, String source, String text, boolean plan, UUID workspace, Mode mode);
}
