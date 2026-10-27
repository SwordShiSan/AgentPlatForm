package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.domain.harness.HarnessPorts.*;
import com.agentplatform.types.harness.HarnessData.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual SDK error observation, no provider and no physical shell claim. */
class KnownToolFailureProbeTest {
    @TempDir Path root;
    @Test void failedToolCommitsOnceAndSdkCanReasonOnTheErrorWithoutRetryingIt() throws Exception {
        var scope=FileProbeStore.scope();var store=new FileProbeStore(root.resolve("state"));
        var models=new AtomicInteger();var executions=new AtomicInteger();
        ModelGateway model=(s,m,t)->{
            if(models.incrementAndGet()==1)return CompletableFuture.completedFuture(new Round("",List.of(FileProbeStore.call("one")),"tool_calls",4,2));
            assertTrue(m.stream().flatMap(v->v.observations().stream()).anyMatch(v->v.status()==OperationStatus.FAILED));
            return CompletableFuture.completedFuture(new Round("The command failed with a known exit code.",List.of(),"stop",4,3));
        };
        var factory=new AgentScopeRuntimeFactory(model,(s,c)->Decision.ALLOW,(s,c)->{
            executions.incrementAndGet();return CompletableFuture.completedFuture(new Observation(c.id(),c.name(),"exitCode=7",OperationStatus.FAILED));
        },store,store,FileProbeStore::definitions,root.resolve("work"));
        LoopBoundary boundary=new LoopBoundary(){
            public CompletionStage<List<Message>> beforeModel(Scope s,List<Message> h){return store.beforeModel(s,h);}
            public CompletionStage<Void> afterModelCommitted(Scope s,Turn t){return store.afterModelCommitted(s,t);}
            public CompletionStage<Void> beforeTool(Scope s,Call c){return store.beforeTool(s,c);}
            public CompletionStage<Void> afterToolCommitted(Scope s,Call c,Observation o){return store.afterToolCommitted(s,c,o);}
            public CompletionStage<Completion> beforeStop(Scope s,Message m){return CompletableFuture.completedFuture(Completion.PASS);}
        };
        try(var run=factory.prepare(new Request(scope,Bundle.baseline(),List.of(Message.text(Role.USER,"report a known error")),false,List.of(),1),boundary)){
            assertEquals(Completion.PASS,run.start().toCompletableFuture().get(20,TimeUnit.SECONDS).completion());
        }
        assertEquals(2,models.get());assertEquals(1,executions.get());
        assertEquals(OperationStatus.FAILED,store.journal(scope).operations().get("one").status());
    }
}
