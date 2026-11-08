package com.agentplatform.domain.capability;

public interface CapabilityMaterializationRunnerPort {
    CapabilityMaterializationReceipt materialize(CapabilityMaterializationCommand command);
    CapabilityMaterializationReceipt query(CapabilityMaterializationCommand command);
}
