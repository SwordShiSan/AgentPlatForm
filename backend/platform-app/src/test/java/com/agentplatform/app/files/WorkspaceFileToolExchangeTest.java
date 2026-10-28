package com.agentplatform.app.files;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.types.harness.HarnessData.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceFileToolExchangeTest {
    private final ChatRepository chat=mock(ChatRepository.class);
    private final Lease lease=new Lease(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID());
    private final Call write=new Call("write-one","workspace_write",Map.of(),false);
    private final ScheduledExecutorService timers=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private Observation value(Call call){return new Observation(call.id(),call.name(),"saved",OperationStatus.SUCCEEDED);}
    @AfterEach void close(){executor.shutdownNow();timers.shutdownNow();}
    @Test void successfulWorkWaitsForTheOriginalPhysicalStopReceipt()throws Exception {
        var ready=new CountDownLatch(1);var stopped=new AtomicBoolean();
        when(chat.workspaceFileStopped(lease,write)).thenAnswer(invocation->stopped.get());
        var exchange=new WorkspaceFileToolExchange(chat,lease,write,cancel->{ready.countDown();return value(write);},executor,timers);
        assertTrue(ready.await(2,TimeUnit.SECONDS));assertFalse(exchange.terminated().toCompletableFuture().isDone());
        assertFalse(exchange.result().toCompletableFuture().isDone());stopped.set(true);
        assertEquals(OperationStatus.SUCCEEDED,exchange.result().toCompletableFuture().get(2,TimeUnit.SECONDS).status());
        exchange.terminated().toCompletableFuture().get(2,TimeUnit.SECONDS);
    }
    @Test void cancellationRequestsDurableStopButStillWaitsForLocalTransportAndChild()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var stopped=new AtomicBoolean();
        when(chat.workspaceFileStopped(lease,write)).thenAnswer(invocation->stopped.get());
        var exchange=new WorkspaceFileToolExchange(chat,lease,write,cancel->{entered.countDown();release.await();return value(write);},executor,timers);
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS));exchange.cancel();exchange.cancel();
            verify(chat,times(1)).requestWorkspaceToolStop(lease,write,WorkspaceToolLedger.StopReason.CANCEL);
            assertFalse(exchange.terminated().toCompletableFuture().isDone());
            stopped.set(true);assertFalse(exchange.terminated().toCompletableFuture().isDone());
        }finally{release.countDown();}
        exchange.terminated().toCompletableFuture().get(2,TimeUnit.SECONDS);
        assertThrows(ExecutionException.class,()->exchange.result().toCompletableFuture().get());
    }
    @Test void databaseFailureCannotTurnAReturnedResultIntoStopProof()throws Exception {
        when(chat.workspaceFileStopped(lease,write)).thenThrow(new ChatFailure(503,"CHAT_STORAGE_UNAVAILABLE"));
        var returned=new CountDownLatch(1);
        var exchange=new WorkspaceFileToolExchange(chat,lease,write,cancel->{returned.countDown();return value(write);},executor,timers);
        assertTrue(returned.await(2,TimeUnit.SECONDS));
        assertThrows(TimeoutException.class,()->exchange.terminated().toCompletableFuture().get(250,TimeUnit.MILLISECONDS));
        assertFalse(exchange.result().toCompletableFuture().isDone());
    }
}
