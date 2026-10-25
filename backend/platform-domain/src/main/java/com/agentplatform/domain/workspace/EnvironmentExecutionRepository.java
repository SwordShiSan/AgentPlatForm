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
    void registerRunner(RunnerInstance instance);
    void checkRunner(Command command, UUID runnerInstanceId);
    Receipt accept(Command command, String requestHash, UUID runnerInstanceId);
    Receipt receipt(Command command);
    /** Exact prior-generation stop provenance, not a new physical observation. */
    default SandboxRuntime.Stop priorStop(Command command) { return null; }
    void commitReceipt(Command command, Receipt receipt, UUID runnerInstanceId);
    /** V3 only: persist exact removal after the immutable stop receipt committed. */
    default Receipt confirmCleanup(Command command, SandboxRuntime.Removal removal, UUID runnerInstanceId) {
        throw new UnsupportedOperationException("Lifecycle cleanup is not supported by this adapter");
    }
    void complete(Command command, Receipt receipt);
    void uncertain(Command command, String code);
    void quarantine(UUID id, String reason, String payloadHash);
}
