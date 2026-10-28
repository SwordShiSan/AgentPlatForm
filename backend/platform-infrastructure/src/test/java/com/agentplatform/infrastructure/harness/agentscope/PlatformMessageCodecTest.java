package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.types.harness.HarnessData.*;
import io.agentscope.core.message.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformMessageCodecTest {
    private final PlatformMessageCodec codec=new PlatformMessageCodec();
    @Test void knownFailureIsAnSdkErrorAndSurvivesStateRoundTrip() {
        var input=new Observation("call","workspace_shell","exitCode=7",OperationStatus.FAILED);
        var sdk=codec.encode(new Message(Role.TOOL,"",List.of(),List.of(input)));
        assertEquals(ToolResultState.ERROR,sdk.getContentBlocks(ToolResultBlock.class).getFirst().getState());
        assertEquals(OperationStatus.FAILED,codec.decode(sdk,Map.of()).observations().getFirst().status());
    }
    @Test void unknownIsNotReclassifiedAsKnownFailureAndLegacyErrorsStayUnknown() {
        var input=new Observation("call","workspace_shell","await original receipt",OperationStatus.UNKNOWN);
        assertEquals(OperationStatus.UNKNOWN,codec.decode(codec.encode(new Message(Role.TOOL,"",List.of(),List.of(input))),Map.of()).observations().getFirst().status());
        var sdk=Msg.builder().role(MsgRole.TOOL).content(ToolResultBlock.error("legacy error").withIdAndName("old","probe")).build();
        assertEquals(OperationStatus.UNKNOWN,codec.decode(sdk,Map.of()).observations().getFirst().status());
    }
    @Test void aPreparedOrRunningOperationCannotBeAdvertisedAsAResult() {
        assertThrows(IllegalArgumentException.class,()->codec.toolResult(new Observation("call","tool","",OperationStatus.RUNNING)));
    }
}
