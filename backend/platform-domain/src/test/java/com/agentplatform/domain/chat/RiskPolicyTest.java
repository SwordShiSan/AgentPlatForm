package com.agentplatform.domain.chat;

import com.agentplatform.types.TaskPermissionData.Mode;
import com.agentplatform.types.harness.HarnessData.Decision;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiskPolicyTest {
    private final RiskPolicy policy = new RiskPolicy();
    @Test void productionBundleMatchesTheCompositionRegistry() {
        assertDoesNotThrow(() -> com.agentplatform.domain.harness.CompositionRules.validate(ChatRuntime.bundle(),1,1,false));
    }
    @Test void fullNeverOverridesAclPlanAvailabilityOrMandatoryDecisions() {
        for (Mode mode : Mode.values()) {
            assertEquals(Decision.DENY, policy.decide(new RiskPolicy.Facts(false,false,true,true,RiskPolicy.Mandatory.NONE,mode,true,true)).decision());
            assertEquals(Decision.DENY, policy.decide(new RiskPolicy.Facts(true,true,true,true,RiskPolicy.Mandatory.NONE,mode,true,true)).decision());
            assertEquals(Decision.DENY, policy.decide(new RiskPolicy.Facts(true,false,false,true,RiskPolicy.Mandatory.NONE,mode,true,true)).decision());
            assertEquals(Decision.DENY, policy.decide(new RiskPolicy.Facts(true,false,true,false,RiskPolicy.Mandatory.NONE,mode,true,true)).decision());
            assertEquals(Decision.DENY, policy.decide(new RiskPolicy.Facts(true,false,true,true,RiskPolicy.Mandatory.DENY,mode,true,true)).decision());
            assertEquals(Decision.ASK, policy.decide(new RiskPolicy.Facts(true,false,true,true,RiskPolicy.Mandatory.ASK,mode,true,true)).decision());
        }
    }
    @Test void fullAndGrantOnlySkipOrdinaryConfirmation() {
        assertEquals(Decision.ALLOW, policy.decide(new RiskPolicy.Facts(true,false,true,true,RiskPolicy.Mandatory.NONE,Mode.FULL,false,false)).decision());
        assertEquals(Decision.ALLOW, policy.decide(new RiskPolicy.Facts(true,false,true,true,RiskPolicy.Mandatory.NONE,Mode.RISK_BASED,true,false)).decision());
        assertEquals(Decision.ASK, policy.decide(new RiskPolicy.Facts(true,false,true,true,RiskPolicy.Mandatory.NONE,Mode.RISK_BASED,false,false)).decision());
        assertEquals(Decision.ALLOW, policy.decide(new RiskPolicy.Facts(true,false,true,true,RiskPolicy.Mandatory.NONE,Mode.RISK_BASED,false,true)).decision());
    }
    @Test void scopesMatchSegmentsWithNfcAndRejectInvalidPaths() {
        assertTrue(RiskPolicy.contains("docs", "docs/a.txt"));assertTrue(RiskPolicy.contains("", "docs/a.txt"));
        assertFalse(RiskPolicy.contains("docs", "docs-other/a.txt"));assertFalse(RiskPolicy.contains("docs", "Docs/a.txt"));
        assertTrue(RiskPolicy.contains("caf\u00e9", "cafe\u0301/a"));
        for (String path : new String[]{"/etc", "../a", "a/../b", "a//b", "a/", "C:/a", "a\\b", "a\u0000b"})
            assertThrows(IllegalArgumentException.class, () -> RiskPolicy.path(path), path);
    }
}
