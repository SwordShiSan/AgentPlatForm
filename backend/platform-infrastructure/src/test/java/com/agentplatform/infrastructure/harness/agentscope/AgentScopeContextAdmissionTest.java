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
}
