package com.agentplatform.domain.capability;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapabilityBindingsTest {
    private static CapabilityBindings.Selection selection() {
        return new CapabilityBindings.Selection(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test void missingWorkspaceInheritsWhileEmptyWorkspaceClears() {
        var personal = new ArrayList<>(List.of(selection()));
        var inherited = CapabilityBindings.resolve(personal, null);
        personal.clear();
        assertEquals(CapabilityBindings.Source.PERSONAL, inherited.source());
        assertEquals(1, inherited.selections().size());
        var cleared = CapabilityBindings.resolve(inherited.selections(), List.of());
        assertEquals(CapabilityBindings.Source.WORKSPACE, cleared.source());
        assertTrue(cleared.selections().isEmpty());
    }

    @Test void rejectsDuplicateCapabilitiesAndOverLimit() {
        var first = selection();
        assertThrows(IllegalArgumentException.class, () -> CapabilityBindings.resolve(
                List.of(first, new CapabilityBindings.Selection(first.capabilityId(), UUID.randomUUID())), null));
        var many = new ArrayList<CapabilityBindings.Selection>();
        for (int i = 0; i < 101; i++) many.add(selection());
        assertThrows(IllegalArgumentException.class, () -> CapabilityBindings.resolve(List.of(), many));
    }
}
