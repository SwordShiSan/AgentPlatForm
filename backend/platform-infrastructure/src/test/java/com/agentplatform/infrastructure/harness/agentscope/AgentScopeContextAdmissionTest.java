package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.ContextPolicy;
import com.agentplatform.domain.chat.WorkspaceTools;
import com.agentplatform.types.harness.HarnessData.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentScopeContextAdmissionTest {
    @Test void fullConfiguredWindowIsUsableAndOversizeInputIsRejectedIntact() {
        var standard=ContextPolicy.current(262_144,4_096);var extended=ContextPolicy.current(524_288,8_192);
        // Select a deterministic input between both real SDK-estimated capacities;
        // do not assume English characters have an exact billable token ratio.
        int low=60_000,high=125_000;
        while(low<high){int middle=(low+high)/2;long measured=AgentScopeContextAdmission.estimate(List.of(Message.text(Role.USER,"context ".repeat(middle))),WorkspaceTools.DEFINITIONS);
            if(measured<300_000)low=middle+1;else high=middle;}
        String text="context ".repeat(low);
        var history=List.of(Message.text(Role.USER,text));
        long estimate=AgentScopeContextAdmission.estimate(history,WorkspaceTools.DEFINITIONS);
        assertTrue(estimate>standard.inputLimit(),"Fixture must exercise the 256K boundary");
        assertTrue(estimate<extended.inputLimit(),"Fixture must fit the configured 512K window; estimate="+estimate);
        assertThrows(ChatFailure.class,()->AgentScopeContextAdmission.requireFits(history,WorkspaceTools.DEFINITIONS,standard));
        assertEquals(estimate,AgentScopeContextAdmission.requireFits(history,WorkspaceTools.DEFINITIONS,extended));
        assertEquals(text,history.getFirst().text());
    }
    @Test void largeContextUsesFrozenPolicyInsteadOfLegacyCeiling() {
        var history=List.of(Message.text(Role.USER,"context ".repeat(4_000)));
        assertThrows(ChatFailure.class,()->AgentScopeContextAdmission.requireFits(history,List.of()));
        long estimate=AgentScopeContextAdmission.requireFits(history,List.of(),ContextPolicy.current(262_144,4_096));
        assertTrue(estimate>7_168);
    }
    @Test void shortPlanAndApprovedRevisionFitWithTheActualWorkspaceSchemas() {
        var history = List.of(Message.text(Role.USER,"Write 666 and seal the declared output."),
                Message.text(Role.ASSISTANT,"Save plan and request review. ".repeat(50)),
                Message.text(Role.TOOL,"Approved immutable plan: "+"Save plan and request review. ".repeat(50)));
        assertDoesNotThrow(() -> AgentScopeContextAdmission.requireFits(history,WorkspaceTools.DEFINITIONS));
        assertTrue(AgentScopeContextAdmission.estimate(history,WorkspaceTools.DEFINITIONS)
                > AgentScopeContextAdmission.estimate(history,List.of()));
    }
    @Test void multilingualAndOversizedRequiredHistoryIsNotSilentlyDropped() {
        var shortText=List.of(Message.text(Role.USER,"计划666"));
        var longText=List.of(Message.text(Role.USER,"计划".repeat(2000)));
        assertTrue(AgentScopeContextAdmission.estimate(longText,List.of())
                > AgentScopeContextAdmission.estimate(shortText,List.of()));
        assertEquals("CONTEXT_REQUIRED_TOO_LARGE",assertThrows(ChatFailure.class,
                () -> AgentScopeContextAdmission.requireFits(longText,WorkspaceTools.DEFINITIONS)).code());
    }
    @Test void aModestChinesePlanAndItsApprovedCopyFitWithoutRemovingEither() {
        String plan="在绑定工作区写入666并封存输出文件。".repeat(32);
        var history=List.of(Message.text(Role.USER,"请制定计划，确认后实际执行。"),
                Message.text(Role.ASSISTANT,plan),Message.text(Role.TOOL,"已批准的完整计划："+plan));
        assertDoesNotThrow(()->AgentScopeContextAdmission.requireFits(history,WorkspaceTools.DEFINITIONS));
    }
}
