package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.domain.harness.HarnessPorts.ToolExchange;
import com.agentplatform.types.TaskPermissionData.Mode;
import com.agentplatform.types.PlanInteractionData.*;
import com.agentplatform.types.harness.HarnessData;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual PG+SDK admission only. The transport is a deterministic port, not a shell/isolation claim. */
class WorkspaceToolAdmissionPostgresTest extends WorkspaceToolsMigrationPostgresTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path work;
    private Lease claim(Task task){
        UUID relay=UUID.randomUUID();var publication=repository.publications(relay).stream().filter(p->p.envelope().aggregateId().equals(task.id())).findFirst().orElseThrow();
        repository.published(relay,publication,true);var lease=repository.claim(publication.envelope(),UUID.randomUUID());assertNotNull(lease);return lease;
    }
    private Task enabled(Mode mode){
        repository=ChatPersistenceFixture.create(pool,true);
        return repository.firstAuthorized(actor,UUID.randomUUID(),"R44 policy probe",UUID.fromString("0199e000-0000-7000-8000-000000000002"),"PLATFORM","Exercise durable admission, not a physical command",false,workspace,mode).task();
    }
    private ChatProvider provider(AtomicInteger calls){return new ChatProvider(){
        public boolean real(){return false;}
        public CompletionStage<Result> stream(String key,List<HarnessData.Message> m,java.util.function.Consumer<String> text){throw new AssertionError("Typed provider required");}
        public Exchange openTyped(String model,String key,List<HarnessData.Message> m,List<HarnessData.ToolDefinition> definitions,java.util.function.Consumer<String> text){
            assertTrue(definitions.stream().anyMatch(d->d.name().equals("workspace_shell")));int index=calls.getAndIncrement();assertTrue(index<2,"No replacement model call is allowed on approval resume");
            var round=index==0?new HarnessData.Round("",List.of(new HarnessData.Call("shell-one","workspace_shell",Map.of("command","printf 666","cwd","","timeoutSeconds",20,"outputs",List.of()),false)),"tool_calls",12,8)
                    :new HarnessData.Round("Deterministic admission probe finished; no physical shell was run.",List.of(),"stop",12,8);
            if(!round.text().isEmpty())text.accept(round.text());
            return new Exchange(){public CompletionStage<Result> result(){return CompletableFuture.completedFuture(new Result(round,true,"r44-admission-"+index));}public CompletionStage<Void> terminated(){return CompletableFuture.completedFuture(null);}public void cancel(){}};
        }
    };}
    private void run(Lease lease,ChatProvider provider,AtomicInteger admissions){
        try(var timers=Executors.newScheduledThreadPool(2);var execution=new ChatExecution(repository,provider,new com.agentplatform.infrastructure.chat.CredentialCipher(new byte[32]),work,timers)){
            execution.configureWorkspaceTools(new WorkspaceToolRuntime(){
                public List<HarnessData.ToolDefinition> definitions(Lease l){return WorkspaceTools.DEFINITIONS;}
                public void prepare(Lease l,List<HarnessData.Call> calls){repository.prepareWorkspaceTools(l,calls,"sha256:"+"a".repeat(64),"b".repeat(64));}
                public void admit(Lease l,HarnessData.Call call){repository.startTool(l,call);}
                public ToolExchange open(Lease l,HarnessData.Call call){
                    assertNotNull(repository.workspaceToolIntent(l,call));admissions.incrementAndGet();
                    return new ToolExchange(){public CompletionStage<HarnessData.Observation> result(){return CompletableFuture.completedFuture(new HarnessData.Observation(call.id(),call.name(),"Admission confirmed; deterministic transport only",HarnessData.OperationStatus.SUCCEEDED));}public CompletionStage<Void> terminated(){return CompletableFuture.completedFuture(null);}public void cancel(){}};
                }
            });execution.run(lease);
        }
    }
    @Test void fullModeAdmitsOneOriginalShellWithoutAnApprovalInteraction() throws Exception {
        var task=enabled(Mode.FULL);var calls=new AtomicInteger();var admissions=new AtomicInteger();run(claim(task),provider(calls),admissions);
        assertEquals("SUCCEEDED",repository.task(actor,task.id()).status());assertEquals(1,admissions.get());assertEquals(2,calls.get());
        assertEquals("0",scalar("SELECT count(*) FROM pf.task_interaction WHERE task_id='"+task.id()+"'"));
        assertEquals("SUCCEEDED",scalar("SELECT status FROM pf.tool_operation WHERE task_id='"+task.id()+"'"));
    }
    @Test void riskModeAsksThenResumesTheOriginalCallWithoutAnotherModelRequest() throws Exception {
        var task=enabled(Mode.RISK_BASED);var calls=new AtomicInteger();var admissions=new AtomicInteger();var provider=provider(calls);
        run(claim(task),provider,admissions);assertEquals("WAITING_INPUT",repository.task(actor,task.id()).status());assertEquals(0,admissions.get());
        var pending=repository.pending(actor,task.conversationId()).getFirst();assertEquals(Kind.TOOL_APPROVAL,pending.kind());
        repository.answer(actor,task.id(),pending.interactionId(),UUID.randomUUID(),new Answer(pending.version(),Decision.ALLOW_ONCE,"",List.of()));
        run(claim(task),provider,admissions);assertEquals("SUCCEEDED",repository.task(actor,task.id()).status());assertEquals(1,admissions.get());assertEquals(2,calls.get());
        assertEquals("1",scalar("SELECT count(*) FROM pf.tool_approval_consumption WHERE task_id='"+task.id()+"'"));
    }
    @Test void contentChangeInvalidatesThePendingCommandButNotItsImmutableIntent() throws Exception {
        var task=enabled(Mode.RISK_BASED);var calls=new AtomicInteger();var admissions=new AtomicInteger();run(claim(task),provider(calls),admissions);
        var pending=repository.pending(actor,task.conversationId()).getFirst();
        sql("UPDATE pf.workspace SET content_revision=content_revision+1 WHERE id='"+workspace+"'");
        assertEquals(412,assertThrows(ChatFailure.class,()->repository.answer(actor,task.id(),pending.interactionId(),UUID.randomUUID(),new Answer(pending.version(),Decision.ALLOW_ONCE,"",List.of()))).status());
        assertEquals(0,admissions.get());assertTrue(repository.pending(actor,task.conversationId()).getFirst().approval().stale());
    }
}
