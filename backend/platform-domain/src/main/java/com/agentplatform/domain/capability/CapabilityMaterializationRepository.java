package com.agentplatform.domain.capability;

import java.util.UUID;

/** Durable, single-use runner command and receipt. */
public interface CapabilityMaterializationRepository {
    record Record(UUID operationId,String commandJson,String requestHash,String state,String receiptJson,
            String receiptHash,String errorCode,long revision) {}
    boolean currentLease(CapabilityMaterializationCommand command);
    boolean accept(CapabilityMaterializationCommand command,String requestHash,String commandJson);
    Record find(UUID operationId);
    void committed(CapabilityMaterializationCommand command,String receiptJson,String receiptHash);
    void failed(CapabilityMaterializationCommand command,String code,String receiptJson,String receiptHash);
    void blocked(CapabilityMaterializationCommand command,String code,String receiptJson,String receiptHash);
}
