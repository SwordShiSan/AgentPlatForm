package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.domain.chat.PlanControls;
import com.agentplatform.types.harness.HarnessData.*;
import io.agentscope.core.message.ToolUseBlock;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PlanControlProbeTest {
    @TempDir Path root;
    @Test void actualSdkAskStopsAndRestoresInANewJvmAndAttempt() throws Exception {
        process("start");
        assertTrue(Files.exists(root.resolve("stopped.txt")));
        assertFalse(Files.exists(root.resolve("resumed.txt")));
        process("resume");
        assertNotEquals(Files.readString(root.resolve("stopped.txt")),Files.readString(root.resolve("resumed.txt")));
        var journal = new FileProbeStore(root).journal(FileProbeStore.scope());
        assertEquals(2,journal.turns().size());
        assertEquals(1,journal.operations().size());
        assertEquals(OperationStatus.SUCCEEDED,journal.operations().get("plan-review-1").status());
    }
    @Test void typedNestedArgumentsRejectUnknownFieldsAndInvalidOptions() {
        var codec = new PlatformMessageCodec();
        var defs = new HashMap<String,ToolDefinition>(); PlanControls.DEFINITIONS.forEach(d -> defs.put(d.name(),d));
        for (String json : List.of("{\"extra\":true}","{\"question\":\"Q\",\"optional\":false,\"options\":[{\"id\":\"x\",\"label\":\"x\",\"secret\":\"x\"}]}",
                "{\"question\":\"Q\",\"optional\":false,\"options\":[{\"id\":\"x\",\"label\":\"A\"},{\"id\":\"x\",\"label\":\"B\"}]}")) {
            String name=json.contains("question")?"platform_ask_user":"platform_plan_review";
            var tool=ToolUseBlock.builder().id("invalid").name(name).content(json).build();
            assertThrows(IllegalArgumentException.class,()->codec.decodeTypedCall(tool,defs));
        }
    }
    private void process(String mode) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        Path log=root.resolve(mode+".log");
        var child=new ProcessBuilder(java,"-Xmx160m","-cp",System.getProperty("java.class.path"),PlanControlProbeProcess.class.getName(),root.toString(),mode)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!child.waitFor(35,TimeUnit.SECONDS)){child.destroyForcibly();fail("SDK probe timed out");}
        assertEquals(0,child.exitValue(),()->{try{return Files.readString(log);}catch(Exception e){return e.toString();}});
    }
}
