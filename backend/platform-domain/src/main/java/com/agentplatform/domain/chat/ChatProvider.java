package com.agentplatform.domain.chat;

import com.agentplatform.types.harness.HarnessData.*;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public interface ChatProvider {
    record Result(Round round, boolean usageKnown, String requestId) {}
    CompletionStage<Result> stream(String apiKey,List<Message> input,Consumer<String> onText);
    default CompletionStage<Result> stream(String model,String apiKey,List<Message> input,Consumer<String> onText) {
        if (!"deepseek-flash".equals(model)) throw new ChatFailure(422,"MODEL_CAPABILITY_MISMATCH");
        return stream(apiKey,input,onText);
    }
    boolean real();
}
