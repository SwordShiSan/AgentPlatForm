package com.agentplatform.domain.harness;

import com.agentplatform.domain.chat.ChatRuntime;
import com.agentplatform.types.harness.HarnessData.Bundle;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LargeContextCompositionTest {
    @Test void bothFrozenProductionContextBundlesAndLegacyBundlesAreAvailable() {
        for(var bundle:new Bundle[]{ChatRuntime.bundle(),ChatRuntime.workspaceBundle(),ChatRuntime.contextBundle(false),ChatRuntime.contextBundle(true),
                ChatRuntime.evidenceBundle(false,false),ChatRuntime.evidenceBundle(true,false),ChatRuntime.evidenceBundle(false,true),ChatRuntime.evidenceBundle(true,true)})
            assertDoesNotThrow(()->CompositionRules.validate(bundle,1,1,false));
    }
    @Test void newContextIdentityDoesNotPermitOtherComponentOrAuthorityChanges() {
        var original=ChatRuntime.contextBundle(true);var components=new TreeMap<>(original.components());components.put("loop","unregistered-loop");
        var changed=new Bundle(original.sdkVersion(),original.contractVersion(),original.stateSchemaVersion(),original.codecVersion(),original.modelFamily(),components);
        assertThrows(IllegalArgumentException.class,()->CompositionRules.validate(changed,1,1,false));
        assertThrows(IllegalArgumentException.class,()->CompositionRules.validate(original,2,1,false));
        assertThrows(IllegalArgumentException.class,()->CompositionRules.validate(original,1,1,true));
    }
}
