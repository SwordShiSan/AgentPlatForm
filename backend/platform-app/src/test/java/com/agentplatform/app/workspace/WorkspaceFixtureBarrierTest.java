package com.agentplatform.app.workspace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceFixtureBarrierTest {
    @Test void productionAndUnscopedCompositionsCannotEnableFaults() {
        for(String point:new String[]{"claim-before-commit","claim-after-commit-before-ack","ack-before-runner","helper-exited-before-receipt"}) {
            assertThrows(IllegalStateException.class,()->WorkspaceFixtureBarrier.configured(point,"r31",false,false));
            assertThrows(IllegalStateException.class,()->WorkspaceFixtureBarrier.configured(point,"r31",true,true));
            assertThrows(IllegalStateException.class,()->WorkspaceFixtureBarrier.configured(point,"other",true,false));
            assertNotNull(WorkspaceFixtureBarrier.configured(point,"r31",true,false));
        }
        assertThrows(IllegalStateException.class,()->WorkspaceFixtureBarrier.configured("arbitrary-command","r31",true,false));
    }
    @Test void defaultCompositionNeverCreatesAMarkerOrBlocks() {
        assertTimeout(java.time.Duration.ofSeconds(1),()->{
            WorkspaceFixtureBarrier.none().await("claim-before-commit");
            WorkspaceFixtureBarrier.configured("","",false,true).await("helper-exited-before-receipt");
        });
    }
    @Test void r32EffectWindowsRequireTheirOwnStageAndIsolatedProfile() {
        for(String point:new String[]{"environment-claim-before-commit","environment-claim-after-commit-before-ack","environment-ack-before-runner",
                "environment-runner-accepted","environment-before-create","environment-created-before-start","environment-started-before-readiness",
                "environment-readiness-before-receipt","environment-receipt-before-response","use-output-before-result","use-result-before-response"}) {
            assertNotNull(WorkspaceFixtureBarrier.configured(point,"r32",true,false));
            assertThrows(IllegalStateException.class,()->WorkspaceFixtureBarrier.configured(point,"r31",true,false));
            assertThrows(IllegalStateException.class,()->WorkspaceFixtureBarrier.configured(point,"r32",false,false));
            assertThrows(IllegalStateException.class,()->WorkspaceFixtureBarrier.configured(point,"r32",true,true));
        }
    }
}
