package com.agentplatform.domain.workspace;

import com.agentplatform.domain.workspace.EnvironmentExecutionData.*;
import java.util.List;
import java.util.UUID;

/** Execution facts only. Actual effects and waiting always occur outside its transactions. */
public interface EnvironmentExecutionRepository {
    List<Publication> publications(UUID relayOwner);
    void published(Publication publication, boolean confirmed);
    Command claim(Publication publication, UUID ownerId);
    Command lookup(UUID operationId);
    void check(Command command);
    void checkOwnership(Command command);
    void renew(Command command);
    void beforeCall(Command command);
    Receipt accept(Command command, String requestHash);
    Receipt receipt(Command command);
    void commitReceipt(Command command, Receipt receipt);
    void complete(Command command, Receipt receipt);
    void uncertain(Command command, String code);
    void quarantine(UUID id, String reason, String payloadHash);
}
