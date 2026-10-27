package com.agentplatform.domain.chat;

import com.agentplatform.domain.harness.CompositionRules;
import com.agentplatform.types.harness.HarnessData.Bundle;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatRuntimeBundleTest {
    @Test void workspaceCatalogHasAnExplicitCompatibleBundleWithoutChangingLegacyTextIdentity() {
        var legacy=ChatRuntime.bundle();var workspace=ChatRuntime.workspaceBundle();
        assertNotEquals(legacy.hash(),workspace.hash());assertFalse(legacy.components().containsKey("tools"));
        assertEquals(WorkspaceTools.VERSION,workspace.components().get("tools"));
        assertDoesNotThrow(()->CompositionRules.validate(workspace,1,1,false));
        var tampered=new HashMap<>(workspace.components());tampered.put("tools","unknown-catalog");
        assertThrows(IllegalArgumentException.class,()->CompositionRules.validate(new Bundle(workspace.sdkVersion(),1,1,workspace.codecVersion(),workspace.modelFamily(),tampered),1,1,false));
    }
    @Test void approvalRuntimeHasAnExplicitIdentityAndDoesNotHotRestoreTheOldControlBundle() {
        var current = ChatRuntime.bundle();
        assertDoesNotThrow(() -> CompositionRules.validate(current, 1, 1, false));
        var components = new HashMap<>(current.components());
        components.put("loop", "agentscope-durable-control-v4");
        components.put("context", "canonical-control-input-v3");
        var old = new Bundle(current.sdkVersion(), current.contractVersion(), current.stateSchemaVersion(),
                current.codecVersion(), current.modelFamily(), components);
        assertNotEquals(old.hash(), current.hash());
        assertThrows(IllegalArgumentException.class, () -> CompositionRules.validate(old, 1, 1, false));
        assertDoesNotThrow(() -> CompositionRules.validate(Bundle.baseline(), 1, 1, false));
    }
}
