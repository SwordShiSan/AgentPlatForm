package com.agentplatform.infrastructure.harness.agentscope;

import static org.junit.jupiter.api.Assertions.*;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JacksonJsonCodec;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Public SDK extension experiments. All model responses are in-process deterministic fixtures. */
class AgentScopeExtensionProbeTest {
    private static final Duration LIMIT = Duration.ofSeconds(5);
    private static final class SummaryModel extends ChatModelBase {
        final AtomicInteger calls = new AtomicInteger();
        final Throwable failure;
        SummaryModel(Throwable failure) { this.failure = failure; }
        @Override public String getModelName() { return "deterministic-summary"; }
        @Override protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            return failure == null ? Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("fixture summary").build())).build()) : Flux.error(failure);
        }
    }
    private List<Msg> history() {
        return new ArrayList<>(List.of(Msg.builder().role(MsgRole.USER).textContent("Earlier context. ".repeat(200)).build(),
                Msg.builder().role(MsgRole.USER).textContent("Latest request").build()));
    }
    private CompactionConfig config() {
        return CompactionConfig.builder().triggerTokens(1).keepTokens(1).flushBeforeCompact(false).offloadBeforeCompact(false).prune(null).build();
    }
    @Test void publicCompactorCanBeAdaptedWithoutAddingASecondLoopOrMutatingCanonicalInput() {
        var model = new SummaryModel(null);
        var original = history();
        var result = new ConversationCompactor(model, new MemoryFlushManager(null, model))
                .compactIfNeeded(RuntimeContext.builder().userId("fixture-user").sessionId("fixture-session").build(), original, config(), "fixture-agent", "fixture-session")
                .block(LIMIT).orElseThrow();
        assertEquals(1, model.calls.get());
        assertTrue(result.stream().anyMatch(message -> message.getTextContent().contains("fixture summary")));
        assertEquals("Latest request", result.getLast().getTextContent());
        assertTrue(original.getFirst().getTextContent().startsWith("Earlier context."));
    }
    @Test void interruptedCompactionPropagatesInsteadOfRetryingOrFabricatingASummary() {
        var model = new SummaryModel(new InterruptedException("fixture cancellation"));
        assertThrows(RuntimeException.class, () -> new ConversationCompactor(model, new MemoryFlushManager(null, model))
                .compactIfNeeded(RuntimeContext.empty(), history(), config(), "fixture-agent", "fixture-session").block(LIMIT));
        assertEquals(1, model.calls.get());
    }
    @Test void publicPrivateStateCodecRoundTripIsIndependentFromCanonicalLedgerStorage() {
        var state = AgentState.builder().build();
        state.contextMutable().addAll(history());
        var codec = new JacksonJsonCodec();
        var restored = codec.fromJson(codec.toJson(state), AgentState.class);
        assertNotSame(state, restored);
        assertEquals(state.getContext().stream().map(Msg::getTextContent).toList(), restored.getContext().stream().map(Msg::getTextContent).toList());
        restored.contextMutable().clear();
        assertEquals(2, state.getContext().size());
    }
}
