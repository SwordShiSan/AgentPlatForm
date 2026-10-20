package com.agentplatform.app.chat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryStopMarkerTest {
    @Test void realProviderMarkersRequireExplicitFlagAndProfileAndOnlySafeCheckpointPhases() {
        for(String point:new String[]{"input-checkpoint-after-seal","terminal-checkpoint-after-seal"}) {
            assertFalse(FixtureFaults.admitted(point,false,false,"chat-worker,recovery-provider-acceptance"));
            assertFalse(FixtureFaults.admitted(point,false,true,"chat-worker"));
            assertTrue(FixtureFaults.admitted(point,false,true,"chat-worker,recovery-provider-acceptance"));
        }
        for(String point:new String[]{"response-before-round","tool-after-start","round-before-checkpoint","tool-before-result"})
            assertFalse(FixtureFaults.admitted(point,false,true,"chat-worker,recovery-provider-acceptance"));
        assertFalse(FixtureFaults.admitted("arbitrary",true,true,"recovery-provider-acceptance"));
        assertTrue(FixtureFaults.admitted("tool-after-result",true,false,"recovery-fixture"));
    }
}
