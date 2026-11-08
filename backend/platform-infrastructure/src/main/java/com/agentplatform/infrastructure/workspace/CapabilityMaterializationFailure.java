package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.capability.CapabilityMaterializationReceipt;
import java.util.Objects;

/** A failed physical canary carrying the exact stop evidence, if the container was confirmed stopped. */
public final class CapabilityMaterializationFailure extends RuntimeException {
    private final CapabilityMaterializationReceipt receipt;

    public CapabilityMaterializationFailure(CapabilityMaterializationReceipt receipt, Throwable cause) {
        super(receipt.errorCode(), cause);
        this.receipt = Objects.requireNonNull(receipt);
    }

    public CapabilityMaterializationReceipt receipt() { return receipt; }
}
