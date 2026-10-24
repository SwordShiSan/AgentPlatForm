package com.agentplatform.infrastructure.workspace;

import java.util.Objects;
import java.util.UUID;

/** One auxiliary container allowance. Retention never authorizes another execution. */
public final class AuxiliaryContainerSlot {
    static final class Permit {
        private Permit() {}
    }

    private boolean occupied;
    private Permit active;
    private UUID reconciliationUse;

    synchronized Permit tryAcquire() {
        if (occupied) return null;
        occupied = true;
        active = new Permit();
        return active;
    }

    /** Only the original file use may borrow a retained allowance to confirm stopping. */
    synchronized Permit tryReconcile(UUID use) {
        Objects.requireNonNull(use);
        if (!occupied || active != null || !use.equals(reconciliationUse)) return null;
        active = new Permit();
        return active;
    }

    /** A null use retains an unresolved verifier; file queries cannot release it. */
    synchronized void retain(Permit permit, UUID use) {
        requireActive(permit);
        active = null;
        reconciliationUse = use;
    }

    synchronized void release(Permit permit) {
        requireActive(permit);
        active = null;
        occupied = false;
        reconciliationUse = null;
    }

    private void requireActive(Permit permit) {
        if (permit == null || active != permit || !occupied) {
            throw new IllegalStateException("Auxiliary container permit is not active");
        }
    }
}
