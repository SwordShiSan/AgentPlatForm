package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.PermissionLedger;
import com.agentplatform.domain.chat.ChatData.Actor;
import com.agentplatform.types.TaskPermissionData.*;
import java.util.UUID;

/** Application use cases; atomicity is provided by the existing transactional repository decorator. */
public final class PermissionGrantService {
    private final PermissionLedger ledger;
    public PermissionGrantService(PermissionLedger ledger) { this.ledger = ledger; }
    public View read(Actor actor, UUID task) { return ledger.permissions(actor, task); }
    public Scopes scopes(Actor actor, UUID task) { return ledger.permissionScopes(actor, task); }
    public View mode(Actor actor, UUID task, UUID key, long expected, Mode mode) { return ledger.permissionMode(actor, task, key, expected, mode); }
    public View grant(Actor actor, UUID task, UUID key, long expected, CreateGrant grant) { return ledger.grant(actor, task, key, expected, grant); }
    public View revoke(Actor actor, UUID task, UUID grant, UUID key, long expected) { return ledger.revokeGrant(actor, task, grant, key, expected); }
}
