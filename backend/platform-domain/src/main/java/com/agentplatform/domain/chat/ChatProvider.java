package com.agentplatform.domain.chat;

import com.agentplatform.types.harness.HarnessData.*;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public interface ChatProvider {
    interface Exchange {
        CompletionStage<Result> result();
        CompletionStage<Void> terminated();
        void cancel();
    }
    record Result(Round round, boolean usageKnown, String requestId) {}
    CompletionStage<Result> stream(String apiKey,List<Message> input,Consumer<String> onText);
    default CompletionStage<Result> stream(String model,String apiKey,List<Message> input,Consumer<String> onText) {
        if (!"deepseek-flash".equals(model)) throw new ChatFailure(422,"MODEL_CAPABILITY_MISMATCH");
        return stream(apiKey,input,onText);
    }
    boolean real();
    /** Legacy scripted probes can complete normally but cannot certify cancelled transport. */
    default Exchange open(String model, String key, List<Message> input, Consumer<String> onText) {
        var future = stream(model, key, input, onText).toCompletableFuture();
        var stopped = new java.util.concurrent.CompletableFuture<Void>();
        future.whenComplete((value, error) -> { if (!future.isCancelled()) stopped.complete(null); });
        return new Exchange() {
            public CompletionStage<Result> result() { return future; }
            public CompletionStage<Void> terminated() { return stopped; }
            public void cancel() { future.cancel(true); }
        };
    }
}
