package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.EnvironmentUseData.*;
import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnvironmentUseApplicationServiceTest {
    private final EnvironmentUseRepository repository=mock(EnvironmentUseRepository.class);
    private final EnvironmentUseRunnerPort runner=mock(EnvironmentUseRunnerPort.class);
    private final ScheduledExecutorService timers=Executors.newScheduledThreadPool(1);
    private final Actor actor=new Actor(UUID.randomUUID(),UUID.randomUUID());
    private final UUID workspace=UUID.randomUUID(),requestId=UUID.randomUUID();
    private final Request request=new Request(null,SandboxRuntime.Probe.PYTHON_666);
    private final Command command=new Command(UUID.randomUUID(),actor.spaceId(),workspace,UUID.randomUUID(),actor.userId(),UUID.randomUUID(),1,2,
            UUID.randomUUID(),1,200001,"sha256:"+"a".repeat(64),"b".repeat(64),"c".repeat(64),"2026-10-22T00:00:00Z",request.kind(),"d".repeat(64),
            request,Instant.now().plusSeconds(120),Instant.now().plusSeconds(135));
    private final Receipt receipt=new Receipt(command.id(),"RUNNING","KNOWN","f".repeat(64));
    private final Fact fact=new Fact(command.id(),command.environmentId(),1,2,command.requestHash(),UUID.randomUUID(),"KNOWN",
            new SandboxRuntime.Output("1".repeat(64),0,"666\n","",false,false,false,null),null,null);
    @AfterEach void stopTimers() {timers.shutdownNow();}
    private EnvironmentUseApplicationService service() {return new EnvironmentUseApplicationService(repository,runner,timers);}
    @BeforeEach void prepare() {
        when(repository.lookup(actor,command.id())).thenReturn(command);
        when(repository.admit(eq(actor),eq(workspace),eq(requestId),any(),eq(request))).thenReturn(new Admission(command,true));
        when(repository.receipt(command)).thenReturn(receipt);when(repository.result(actor,command.id())).thenReturn(fact);
    }
    @Test void detachedReconciliationOnlyQueriesOriginalCommand() {
        var pending=new Receipt(command.id(),"UNKNOWN","UNKNOWN",null);
        when(repository.receipt(command)).thenReturn(pending,receipt,receipt);
        when(runner.queryUse(command)).thenReturn(receipt);
        assertEquals(receipt,service().reconcile(actor,workspace,command.id()));
        verify(runner,never()).executeUse(any());verify(repository,never()).admit(any(),any(),any(),any(),any());
        verify(repository,never()).renew(any());verify(repository).complete(command);
        verify(repository,times(2)).lookup(actor,command.id());
    }
    @Test void detachedReconciliationRetainsUnconfirmedOwnership() {
        when(repository.receipt(command)).thenReturn(new Receipt(command.id(),"UNKNOWN","UNKNOWN",null));
        when(runner.queryUse(command)).thenThrow(new WorkspaceFailure(503,"RUNNER_RESULT_UNAVAILABLE"));
        assertEquals("ENVIRONMENT_USE_RECONCILIATION_REQUIRED",assertThrows(WorkspaceFailure.class,()->service().reconcile(actor,workspace,command.id())).code());
        verify(repository,never()).complete(any());verify(runner,never()).executeUse(any());verify(repository,never()).renew(any());
    }
    @Test void alreadyCommittedReconciliationDoesNotReapplyOldOwnership() {
        var completed=new Receipt(command.id(),"COMMITTED","KNOWN",receipt.resultHash());
        when(repository.receipt(command)).thenReturn(completed);
        assertEquals(completed,service().reconcile(actor,workspace,command.id()));
        verify(repository,never()).complete(any());verify(repository,never()).renew(any());
        verify(repository,never()).admit(any(),any(),any(),any(),any());verify(runner,never()).executeUse(any());
    }
    @Test void detachedReconciliationRejectsForeignWorkspaceBeforeRunner() {
        assertEquals(404,assertThrows(WorkspaceFailure.class,()->service().reconcile(actor,UUID.randomUUID(),command.id())).status());
        verifyNoInteractions(runner);verify(repository,never()).complete(any());
    }
    @Test void detachedReconciliationRejectsHashThatWasNotPersisted() {
        when(repository.receipt(command)).thenReturn(new Receipt(command.id(),"UNKNOWN","UNKNOWN",null),receipt);
        when(runner.queryUse(command)).thenReturn(new Receipt(command.id(),"UNKNOWN","UNKNOWN","e".repeat(64)));
        assertEquals("RUNNER_RECEIPT_MISMATCH",assertThrows(WorkspaceFailure.class,()->service().reconcile(actor,workspace,command.id())).code());
        verify(repository,never()).complete(any());verify(runner,never()).executeUse(any());
    }
    @Test void responseLossQueriesOriginalUseAndNeverPostsAgain() {
        when(repository.receipt(command)).thenReturn(new Receipt(command.id(),"RUNNING","PENDING",null),receipt);
        when(runner.executeUse(command)).thenThrow(new WorkspaceFailure(503,"RUNNER_RESULT_UNKNOWN"));when(runner.queryUse(command)).thenReturn(receipt);
        assertEquals(fact,service().execute(actor,workspace,requestId,request));
        verify(runner,times(1)).executeUse(command);verify(repository,times(1)).complete(command);
        verify(runner,times(2)).queryUse(command); // Result lookup, then post-commit cleanup query.
    }
    @Test void duplicateCanProjectCommittedFactButCannotBecomeNewExecutor() {
        when(repository.admit(eq(actor),eq(workspace),eq(requestId),any(),eq(request))).thenReturn(new Admission(command,false));
        assertEquals(fact,service().execute(actor,workspace,requestId,request));verifyNoInteractions(runner);verify(repository).complete(command);
    }
    @Test void duplicateUnresolvedUseIsNotResubmitted() {
        when(repository.admit(eq(actor),eq(workspace),eq(requestId),any(),eq(request))).thenReturn(new Admission(command,false));
        when(repository.receipt(command)).thenReturn(new Receipt(command.id(),"UNKNOWN","UNKNOWN",null));
        assertEquals("ENVIRONMENT_USE_RECONCILIATION_REQUIRED",assertThrows(WorkspaceFailure.class,()->service().execute(actor,workspace,requestId,request)).code());
        verifyNoInteractions(runner);verify(repository,never()).complete(any());
    }
    @Test void signedManagementHashMustMatchDurableFactBeforeProjection() {
        when(runner.executeUse(command)).thenReturn(new Receipt(command.id(),"RUNNING","KNOWN","e".repeat(64)));
        assertEquals("RUNNER_RECEIPT_MISMATCH",assertThrows(WorkspaceFailure.class,()->service().execute(actor,workspace,requestId,request)).code());
        verify(repository,never()).complete(any());verify(repository,never()).result(any(),any());
    }
    @Test void committedFactConvergesEvenIfRunnerAndResponseWereLost() {
        when(runner.executeUse(command)).thenThrow(new WorkspaceFailure(503,"RUNNER_RESULT_UNKNOWN"));
        when(runner.queryUse(command)).thenThrow(new WorkspaceFailure(503,"RUNNER_RESULT_UNAVAILABLE"));
        assertEquals(fact,service().execute(actor,workspace,requestId,request));verify(repository).complete(command);
        verify(runner,times(1)).executeUse(command);verify(runner,times(1)).queryUse(command);
    }
}
