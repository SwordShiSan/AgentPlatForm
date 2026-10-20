package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.ChatProvider;
import com.agentplatform.domain.harness.HarnessPorts.PreparedRun;
import com.agentplatform.types.harness.HarnessData.Outcome;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionControlTest {
    private static PreparedRun run(CompletableFuture<Void> stopped) {
        return new PreparedRun() {
            public CompletionStage<Outcome> start(){return new CompletableFuture<>();}
            public CompletionStage<Void> terminated(){return stopped;}
            public boolean cancel(){return true;}
            public boolean interrupt(){return true;}
            public void close(){}
        };
    }
    @Test void cancelledFutureDoesNotCertifyAnOutstandingWriter() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            var control=new ExecutionControl(executor);control.run(run(CompletableFuture.completedFuture(null)));
            var writer=control.enter();control.cancel();
            assertTrue(control.cancellation().toCompletableFuture().isDone());
            assertThrows(ChatFailure.class,()->control.receipt("scope"));
            var stopped=CompletableFuture.supplyAsync(()->control.quiesce(true),executor);
            Thread.sleep(100);assertFalse(stopped.isDone());writer.close();
            assertTrue(stopped.get(2,TimeUnit.SECONDS));assertEquals(64,control.receipt("scope").length());
            assertThrows(ChatFailure.class,control::enter);
        }
    }
    @Test void failedTransportTeardownKeepsStopUnconfirmed() {
        var control=new ExecutionControl(Runnable::run);control.run(run(CompletableFuture.completedFuture(null)));
        control.exchange(new ChatProvider.Exchange() {
            public CompletionStage<ChatProvider.Result> result(){return CompletableFuture.failedFuture(new CancellationException());}
            public CompletionStage<Void> terminated(){return CompletableFuture.failedFuture(new IllegalStateException("transport still active"));}
            public void cancel(){}
        });
        assertFalse(control.quiesce(true));assertThrows(ChatFailure.class,()->control.receipt("scope"));
    }
    @Test void cancellationRemovesScheduledRetryBeforeConfirmingQuiescence() {
        try(var timers=Executors.newSingleThreadScheduledExecutor()) {
            var control=new ExecutionControl(Runnable::run);control.run(run(CompletableFuture.completedFuture(null)));
            var retry=timers.schedule(()->fail("A cancelled attempt cannot retry"),1,TimeUnit.HOURS);
            control.timer(retry);assertTrue(control.quiesce(true));assertTrue(retry.isCancelled());
        }
    }
}
