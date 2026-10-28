package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.domain.chat.PlanControls;
import com.agentplatform.domain.chat.WorkspaceTools;
import com.agentplatform.domain.harness.HarnessPorts.*;
import com.agentplatform.types.harness.HarnessData.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PlanThenToolApprovalProbeTest {
    @TempDir Path root;

    @Test void planConfirmationCanReachANewNestedShellApprovalAndRestoreAgain() throws Exception {
        var base=FileProbeStore.scope();var store=new FileProbeStore(root);
        var review=new Call("review", "platform_plan_review",Map.of(),false);
        var shell=new Call("shell", "workspace_shell",Map.of("command","printf 666","cwd","","timeoutSeconds",30,
                "outputs",List.of(Map.of("path","proof.txt","maxBytes",32))),false);
        var definitions=new ArrayList<ToolDefinition>(PlanControls.DEFINITIONS);definitions.addAll(WorkspaceTools.DEFINITIONS);
        for(int phase=0;phase<3;phase++) {
            int current=phase;var drafts=new ArrayList<CheckpointDraft>();
            var scope=new Scope(base.spaceId(),base.actorId(),base.conversationId(),base.taskId(),UUID.randomUUID().toString(),phase+1,false);
            ModelGateway model=new ModelGateway() {
                public CompletionStage<Round> complete(Scope s,List<Message> h,List<Call> t){throw new AssertionError();}
                public CompletionStage<Round> streamTyped(Scope s,List<Message> h,List<ToolDefinition> t,java.util.function.Consumer<String> delta){
                    return CompletableFuture.completedFuture(current==2?new Round("done",List.of(),"stop",3,1):new Round("",List.of(current==0?review:shell),"tool_calls",3,1));
                }
            };
            RuntimeStatePort manifests=new RuntimeStatePort() {
                public void verifyRestore(Scope s,Bundle b){store.verifyRestore(s,b);}
                public void seal(Scope s,Bundle b){store.seal(s,b);}
                public List<Message> recoveryHistory(Scope s){return store.recoveryHistory(s);}
                public List<Message> checkpointHistory(Scope s){return store.recoveryHistory(s);}
                public boolean atomicCheckpoints(Scope s){return true;}
                public void commitCheckpoint(Scope s,Bundle b,CheckpointDraft d){assertTrue(store.compareAndSwap(s,"checkpoint_agent_state",d.expectedVersion(),d.state())>0);drafts.add(d);}
            };
            ToolCatalogPort catalog=new ToolCatalogPort(){public List<Call> definitions(){return List.of();}public List<ToolDefinition> typedDefinitions(){return definitions;}};
            var factory=new AgentScopeRuntimeFactory(model,(s,c)->current==0||current==1&&c.name().equals("workspace_shell")?Decision.ASK:Decision.ALLOW,
                    (s,c)->CompletableFuture.completedFuture(new Observation(c.id(),c.name(),"approved",OperationStatus.SUCCEEDED)),store,manifests,catalog,root.resolve("work"));
            var approvals=phase==0?List.<Approval>of():List.of(new Approval(phase==1?review:shell,true));
            var request=new Request(scope,Bundle.baseline(),phase==0?List.of(Message.text(Role.USER,"Plan then execute")):List.of(),phase>0,approvals,phase+1);
            try(var run=factory.prepare(request,store)) {
                assertEquals(phase==0?Completion.WAITING_INPUT:phase==1?Completion.WAITING_APPROVAL:Completion.PASS,
                        run.start().toCompletableFuture().get(15,TimeUnit.SECONDS).completion());
                run.terminated().toCompletableFuture().get(5,TimeUnit.SECONDS);
            }
            assertFalse(drafts.isEmpty());var snapshot=drafts.getLast();
            assertTrue(store.compareAndSwap(scope,"agent_state",store.load(scope,"agent_state").version(),snapshot.state())>0);
            store.seal(scope,Bundle.baseline());
        }
    }
}
