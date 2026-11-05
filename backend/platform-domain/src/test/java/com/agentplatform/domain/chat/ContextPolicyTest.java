package com.agentplatform.domain.chat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContextPolicyTest {
    @Test void standardWindowReservesOnlyActualEstimatedInputAndProtectsCapacity() {
        var policy = ContextPolicy.current(262_144, 4_096);
        assertEquals(1_000_000, policy.modelWindow());
        assertEquals(13_108, policy.safetyTokens());
        assertEquals(244_940, policy.inputLimit());
        assertEquals(5_608, policy.reserveTokens(1_000));
        assertEquals("CONTEXT_REQUIRED_TOO_LARGE", assertThrows(ChatFailure.class,
                () -> policy.reserveTokens(policy.inputLimit() + 1)).code());
    }

    @Test void configurableBoundsAndLegacyReservationsAreIndependent() {
        assertEquals(9_216, ContextPolicy.legacy().reserveTokens(300));
        assertEquals(524_288, ContextPolicy.current(524_288, 8_192).taskWindow());
        assertThrows(IllegalArgumentException.class, () -> ContextPolicy.current(65_535, 4_096));
        assertThrows(IllegalArgumentException.class, () -> ContextPolicy.current(524_289, 4_096));
    }
}
