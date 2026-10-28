package com.agentplatform.domain.chat;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceToolsTest {
    @Test void shellFreezesActualCommandAndBoundedOutputsWithoutHostOptions() {
        var values=new ArrayList<WorkspaceTools.OutputFile>();values.add(new WorkspaceTools.OutputFile("结果.txt",1024));
        var request=new WorkspaceTools.Shell("python3 script.py > 结果.txt","",120,values);values.clear();
        assertEquals(1,request.outputs().size());assertEquals("",request.cwd());
        assertThrows(UnsupportedOperationException.class,()->request.outputs().clear());
        assertTrue(WorkspaceTools.DEFINITIONS.stream().allMatch(WorkspaceTools::registered));
    }
    @Test void pathsAndUtf8ByteLimitsCannotBeWidenedByGeneralShellMetadata() {
        for(String path:List.of("../outside","/tmp","a/../b","a\\b"))
            assertThrows(IllegalArgumentException.class,()->new WorkspaceTools.Shell("true",path,1,List.of()));
        assertThrows(IllegalArgumentException.class,()->new WorkspaceTools.Shell("中".repeat(11000),"",1,List.of()));
        assertThrows(IllegalArgumentException.class,()->new WorkspaceTools.Shell("a\0b","",1,List.of()));
        assertThrows(IllegalArgumentException.class,()->new WorkspaceTools.Shell("true","",121,List.of()));
    }
    @Test void outputReservationsRejectDuplicatesAndAggregateOverselling() {
        var one=new WorkspaceTools.OutputFile("result",WorkspaceTools.OUTPUT_BYTES);
        assertThrows(IllegalArgumentException.class,()->new WorkspaceTools.Shell("true","",1,List.of(one,one)));
        assertThrows(IllegalArgumentException.class,()->new WorkspaceTools.Shell("true","",1,List.of(one,new WorkspaceTools.OutputFile("other",1))));
        assertDoesNotThrow(()->new WorkspaceTools.Shell("touch empty","",1,List.of(new WorkspaceTools.OutputFile("empty",0))));
    }
    @Test void replacementRequiresExactVersionAndCreateCannotSmuggleAnOverwrite() {
        String file=UUID.randomUUID().toString(),version=UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class,()->new WorkspaceTools.WriteFile("REPLACE","a","body",file,null,1L));
        assertThrows(IllegalArgumentException.class,()->new WorkspaceTools.WriteFile("CREATE","a","body",file,version,1L));
        assertThrows(IllegalArgumentException.class,()->new WorkspaceTools.WriteFile("CREATE","a","中".repeat(11000),null,null,null));
        assertDoesNotThrow(()->new WorkspaceTools.WriteFile("REPLACE","a","",file,version,1L));
    }
    @Test void workspacePlanMetadataKeepsTheLegacyTextContractAndNeverGrantsPermissions() {
        var legacy=PlanControls.DEFINITIONS.stream().filter(d->d.name().equals("platform_plan_review")).findFirst().orElseThrow();
        var scoped=PlanControls.workspaceDefinitions().stream().filter(d->d.name().equals("platform_plan_review")).findFirst().orElseThrow();
        assertTrue(legacy.description().contains("only text"));assertTrue(scoped.description().contains("same task"));
        assertTrue(scoped.description().contains("separate live policy admission"));assertEquals(legacy.inputSchema(),scoped.inputSchema());assertEquals(legacy.category(),scoped.category());
        assertEquals("scoped-plan-review-v2",ChatRuntime.workspaceBundle().components().get("plan-controls"));assertFalse(ChatRuntime.bundle().components().containsKey("plan-controls"));
    }

}
