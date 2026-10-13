package com.agentplatform.infrastructure.harness.agentscope;

import io.agentscope.core.agent.*;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.*;
import io.agentscope.core.middleware.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed-version API probes; no production credentials, user files or public endpoints. */
class SdkSurfaceProbeTest {
    @TempDir Path root;
    private HarnessAgent.Builder builder(Model model) {
        return HarnessAgent.builder().name("surface-probe").model(model).workspace(root).stateStore(new InMemoryAgentStateStore())
                .disableCompaction().disableToolResultEviction().disableMemoryTools().disableMemoryHooks()
                .disableFilesystemTools().disableShellTool().disableWebTools().disableDynamicSkills().disableDefaultWorkspaceSkills()
                .disableSubagents().disableDynamicSubagents().disableTranscript().disableWorkspaceContext().disableAtPathExpansion()
                .disableToolsConfig().enablePendingToolRecovery(false).enableAgentTracingLog(false).maxRetries(1);
    }
    private Model model(AtomicInteger calls) {
        return new Model() {
            public String getModelName() { return "surface-fixture"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                calls.incrementAndGet();
                return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("answer").build())).finishReason("stop").build());
            }
        };
    }
    @Test void actualPrepareCallStreamEventsAndPlanSwitch() {
        var calls = new AtomicInteger(); RuntimeContext scope = RuntimeContext.builder().userId("fixture-user").sessionId("fixture-session").build();
        try (HarnessAgent agent = builder(model(calls)).build()) {
            agent.enterPlanMode(scope);
            var prepared = agent.prepareCall(List.of(Msg.builder().role(MsgRole.USER).textContent("read-only question").build()), scope);
            assertEquals(0, calls.get(), "prepare must not invoke model");
            assertEquals("answer", prepared.stream().blockLast(Duration.ofSeconds(5)).getTextContent());
            agent.exitPlanMode(scope);
            var events = agent.streamEvents(List.of(Msg.builder().role(MsgRole.USER).textContent("second question").build()), scope).collectList().block(Duration.ofSeconds(5));
            assertNotNull(events); assertFalse(events.isEmpty()); assertEquals(2, calls.get());
        }
    }
    @Test void middlewareOnionOrderAndErrorsPropagate() {
        var trace = new ArrayList<String>(); var calls = new AtomicInteger();
        MiddlewareBase outer = middleware("outer", 100, trace); MiddlewareBase inner = middleware("inner", 10, trace);
        try (HarnessAgent agent = builder(model(calls)).middleware(inner).middleware(outer).build()) {
            agent.streamEvents(Msg.builder().role(MsgRole.USER).textContent("question").build()).collectList().block(Duration.ofSeconds(5));
            assertEquals(List.of("outer:before", "inner:before", "inner:after", "outer:after"), trace);
        }
        Model failed = new Model() {
            public String getModelName() { return "failed-fixture"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) { return Flux.error(new IllegalStateException("FIXTURE_MODEL_ERROR")); }
        };
        try (HarnessAgent agent = builder(failed).build()) {
            assertThrows(RuntimeException.class, () -> agent.streamEvents(Msg.builder().role(MsgRole.USER).textContent("question").build()).collectList().block(Duration.ofSeconds(5)));
        }
    }
    private MiddlewareBase middleware(String name, int order, List<String> trace) {
        return new MiddlewareBase() {
            public int order() { return order; }
            public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext scope, ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
                return Flux.defer(() -> { trace.add(name + ":before"); return next.apply(input).doOnComplete(() -> trace.add(name + ":after")); });
            }
        };
    }
}
