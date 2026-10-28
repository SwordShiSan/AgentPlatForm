package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.WorkspaceTools;
import com.agentplatform.types.harness.HarnessData.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentScopeContextAdmissionTest {
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
