package com.agentplatform.infrastructure.workspace;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class AuxiliaryContainerSlotTest {
    @Test void unknownFileUseCanOnlyReconcileItsOwnStop() {
        var slot = new AuxiliaryContainerSlot();
        var use = UUID.randomUUID();
        var execution = slot.tryAcquire();
        assertNotNull(execution);
        slot.retain(execution, use);
        assertNull(slot.tryAcquire(), "Retaining an unknown helper must not allow a second execution");
        assertNull(slot.tryReconcile(UUID.randomUUID()));
        var observation = slot.tryReconcile(use);
        assertNotNull(observation);
        assertNull(slot.tryReconcile(use));
        slot.release(observation);
        assertNotNull(slot.tryAcquire(), "Confirmed stopping returns exactly one allowance");
    }

    @Test void unresolvedQuotaVerifierCannotBeReleasedByAFileReceipt() {
        var slot = new AuxiliaryContainerSlot();
        var verifier = slot.tryAcquire();
        slot.retain(verifier, null);
        assertNull(slot.tryAcquire());
        assertNull(slot.tryReconcile(UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> slot.release(verifier));
        assertNull(slot.tryAcquire());
    }

    @Test void unsuccessfulStopObservationRetainsTheSameUseWithoutReexecution() {
        var slot = new AuxiliaryContainerSlot();
        var use = UUID.randomUUID();
        slot.retain(slot.tryAcquire(), use);
        var first = slot.tryReconcile(use);
        slot.retain(first, use);
        assertNull(slot.tryAcquire());
        var second = slot.tryReconcile(use);
        assertNotNull(second);
        assertNotSame(first, second);
        assertThrows(IllegalStateException.class, () -> slot.release(first));
        slot.release(second);
        assertNotNull(slot.tryAcquire());
    }

    @Test void stalePermitCannotReleaseNewOwner() {
        var slot = new AuxiliaryContainerSlot();
        var previous = slot.tryAcquire();
        slot.release(previous);
        var current = slot.tryAcquire();
        assertThrows(IllegalStateException.class, () -> slot.release(previous));
        assertThrows(IllegalStateException.class, () -> slot.release(null));
        assertNull(slot.tryAcquire());
        slot.release(current);
        assertNotNull(slot.tryAcquire());
    }

    @Test void competingOriginalUseQueriesHaveOneStopObserver() throws Exception {
        var slot = new AuxiliaryContainerSlot();
        var use = UUID.randomUUID();
        slot.retain(slot.tryAcquire(), use);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); return slot.tryReconcile(use); });
            var second = executor.submit(() -> { start.await(); return slot.tryReconcile(use); });
            start.countDown();
            var a = first.get();
            var b = second.get();
            assertNotEquals(a == null, b == null);
            slot.release(a == null ? b : a);
        }
        assertNotNull(slot.tryAcquire());
    }
}
