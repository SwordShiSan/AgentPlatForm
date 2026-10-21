package com.agentplatform.domain.workspace;

import com.agentplatform.domain.workspace.WorkspaceData.*;
import java.util.List;
import java.util.UUID;

/** Every mutation is implemented behind an application transaction boundary. */
public interface WorkspaceRepository {
    Workspace create(Actor actor, UUID requestId, String requestHash, String name);
    Workspace get(Actor actor, UUID workspaceId);
    List<Workspace> list(Actor actor, UUID before, int limit);
    Workspace retry(Actor actor, UUID workspaceId, UUID requestId, String requestHash, long expectedVersion);
    List<Publication> publications(UUID relayOwner);
    void published(Publication publication, boolean confirmed);
    Command claim(Publication publication, UUID ownerId);
    Command lookup(UUID operationId);
    void renew(Command command);
    void check(Command command);
    void checkOwnership(Command command);
    void beforeCall(Command command);
    Receipt accept(Command command, String requestHash);
    Receipt receipt(Command command);
    void commitReceipt(Command command, Receipt receipt, String receiptJson, String contentHash);
    void complete(Command command, Receipt receipt);
    void uncertain(Command command, String code);
    /** Conservative control-plane projection only; never releases capacity or replaces an owner. */
    int expireUnresolved();
    void quarantine(UUID id, String reason, String payloadHash);
}
