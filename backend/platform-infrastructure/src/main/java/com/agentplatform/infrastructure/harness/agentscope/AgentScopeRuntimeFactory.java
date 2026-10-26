package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.domain.harness.CompositionRules;
import com.agentplatform.domain.harness.HarnessPorts.*;
import com.agentplatform.types.harness.HarnessData.*;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.*;
import io.agentscope.core.middleware.*;
import io.agentscope.core.model.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.tool.*;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** One SDK run per attempt; SDK objects never escape this boundary. No Spring auto-registration. */
public final class AgentScopeRuntimeFactory implements LoopDriver {
    private final ModelGateway model;
    private final PolicyPort policy;
    private final ToolExecutionPort tools;
    private final StateStorage state;
    private final RuntimeStatePort manifests;
    private final Map<String, ToolDefinition> catalog;
    private final Path workspaceRoot;
    private final PlatformMessageCodec codec = new PlatformMessageCodec();

    public AgentScopeRuntimeFactory(ModelGateway model, PolicyPort policy, ToolExecutionPort tools,
                                   StateStorage state, RuntimeStatePort manifests, List<Call> definitions, Path workspaceRoot) {
        this(model, policy, tools, state, manifests, new ToolCatalogPort() {
            public List<Call> definitions() { return definitions; }
        }, workspaceRoot);
    }

    public AgentScopeRuntimeFactory(ModelGateway model, PolicyPort policy, ToolExecutionPort tools,
                                   StateStorage state, RuntimeStatePort manifests, ToolCatalogPort definitions, Path workspaceRoot) {
        this.model = java.util.Objects.requireNonNull(model); this.policy = java.util.Objects.requireNonNull(policy);
        this.tools = java.util.Objects.requireNonNull(tools); this.state = java.util.Objects.requireNonNull(state);
        this.manifests = java.util.Objects.requireNonNull(manifests);
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        var registered = new LinkedHashMap<String, ToolDefinition>();
        for (ToolDefinition definition : definitions.typedDefinitions()) if (registered.putIfAbsent(definition.name(), definition) != null) throw new IllegalArgumentException("Duplicate tool");
        // Stable definition order keeps provider prompts and cross-JVM contract evidence deterministic.
        this.catalog = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(registered));
    }

    @Override public PreparedRun prepare(Request request, LoopBoundary boundary) {
        CompositionRules.validate(request.bundle(), 1, 1, false);
        if (request.restoring()) manifests.verifyRestore(request.scope(), request.bundle());
        return new Execution(request, boundary);
    }

    private final class Execution implements PreparedRun {
        private final Request request;
        private final LoopBoundary boundary;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<Turn> lastTurn = new AtomicReference<>();
        private final AtomicReference<List<Call>> pendingCalls = new AtomicReference<>(List.of());
        private final java.util.concurrent.CopyOnWriteArrayList<CompletableFuture<?>> pendingFutures = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicLong nextTurn;
        private final CompletableFuture<Outcome> result = new CompletableFuture<>();
        private final CompletableFuture<Void> terminated = new CompletableFuture<>();
        private final HarnessAgent agent;
        private final AgentRun<AgentEvent> run;

        Execution(Request request, LoopBoundary boundary) {
            this.request = request; this.boundary = boundary; this.nextTurn = new AtomicLong(request.nextTurn());
            Toolkit toolkit = new Toolkit(ToolkitConfig.builder().parallel(false).build());
            catalog.values().forEach(definition -> toolkit.registerAgentTool(new GuardedTool(definition)));
            RuntimeContext context = RuntimeContext.builder().userId(request.scope().actorId()).sessionId(request.scope().conversationId()).build();
            agent = HarnessAgent.builder().name("platform-runtime").model(new BridgeModel()).toolkit(toolkit)
                    .stateStore(new ScopeStateStore(state, request.scope()))
                    .permissionContext(PermissionContextState.builder().mode(PermissionMode.DEFAULT).build())
                    .workspace(workspaceRoot.resolve(request.scope().spaceId()).resolve(request.scope().conversationId()))
                    .disableCompaction().disableToolResultEviction().disableMemoryTools().disableMemoryHooks()
                    .disableFilesystemTools().disableShellTool().disableWebTools().disableDynamicSkills()
                    .disableDefaultWorkspaceSkills().disableSubagents().disableDynamicSubagents().disableTranscript()
                    .disableWorkspaceContext().disableAtPathExpansion().disableToolsConfig().enablePendingToolRecovery(false)
                    .maxRetries(1).maxIters(Integer.MAX_VALUE).enableAgentTracingLog(false).middleware(new CommitMiddleware()).build();
            // Some control tools are SDK defaults. Remove them before any run, then check the surface.
            for (ToolSchema schema : List.copyOf(agent.getDelegate().getToolkit().getToolSchemas())) {
                if (!catalog.containsKey(schema.getName())) agent.getDelegate().getToolkit().removeTool(schema.getName());
            }
            if (request.restoring()) {
                var privateState = agent.getDelegate().getAgentState(context);
                var priorTools = privateState.getContext().stream().flatMap(message -> message.getContentBlocks(ToolUseBlock.class).stream())
                        .collect(java.util.stream.Collectors.toMap(ToolUseBlock::getId, Function.identity(), (a, b) -> b));
                List<Message> history = manifests.recoveryHistory(request.scope());
                if (history.isEmpty()) throw new IllegalStateException("CHECKPOINT_NOT_COMMITTED");
                List<Msg> restored = history.stream().map(codec::encode).map(message -> message.withContent(message.getContent().stream().map(block -> {
                    if (block instanceof ToolUseBlock tool && priorTools.containsKey(tool.getId())) {
                        ToolUseBlock prior = priorTools.get(tool.getId());
                        if (!codec.decodeTypedCall(prior, catalog).equals(codec.decodeTypedCall(tool, catalog))) throw new IllegalStateException("CHECKPOINT_CALL_MISMATCH");
                        return (ContentBlock) tool.withState(prior.getState());
                    }
                    return block;
                }).toList())).toList();
                privateState.contextMutable().clear(); privateState.contextMutable().addAll(restored);
                agent.getDelegate().saveAgentState(context);
            }
            if (request.scope().planMode()) agent.enterPlanMode(context);
            else agent.exitPlanMode(context);
            List<Msg> input = new ArrayList<>(request.input().stream().map(codec::encode).toList());
            if (!request.approvals().isEmpty()) {
                var approvals = request.approvals().stream().map(approval -> new ConfirmResult(approval.allowed(), codec.toolUse(approval.call()), List.of())).toList();
                input.add(Msg.builder().role(MsgRole.TOOL).metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, approvals)).build());
            }
            run = agent.prepareRun(input, context);
        }

        private void active() {
            if (closed.get()) throw new CancellationException("Run cancelled");
            Throwable error = failure.get();
            if (error != null) throw new IllegalStateException("BOUNDARY_FAILED", error);
        }
        private <T> Mono<T> await(java.util.function.Supplier<CompletionStage<T>> action) {
            return Mono.defer(() -> {
                active(); var future = action.get().toCompletableFuture(); pendingFutures.add(future);
                future.whenComplete((value, error) -> pendingFutures.remove(future));
                if (closed.get()) future.cancel(true);
                return Mono.fromFuture(future);
            })
                    .doOnError(error -> failure.compareAndSet(null, error));
        }

        @Override public CompletionStage<Outcome> start() {
            if (!started.compareAndSet(false, true)) throw new IllegalStateException("Run already subscribed");
            if(manifests.atomicCheckpoints(request.scope())&&!request.restoring())saveCheckpoint(CheckpointPhase.INPUT_READY);
            AtomicBoolean asking = new AtomicBoolean();
            run.stream().doOnNext(event -> { if(event instanceof io.agentscope.core.event.RequireUserConfirmEvent) asking.set(true); }).then(Mono.defer(() -> {
                active();
                Turn turn = lastTurn.get();
                if (asking.get()) {
                    List<Call> pending = turn == null ? restoredPending() : turn.round().calls();
                    boolean control = pending.stream().anyMatch(call -> catalog.get(call.name()).category() == ToolCategory.CONTROL);
                    return await(()->boundary.beforeWaiting(request.scope(),pending)).then(Mono.fromCallable(()->{
                        saveCheckpoint(control ? CheckpointPhase.WAITING_INPUT : CheckpointPhase.TERMINAL);
                        return new Outcome(control ? Completion.WAITING_INPUT : Completion.WAITING_APPROVAL,"",pending);
                    }));
                }
                saveCheckpoint(CheckpointPhase.TERMINAL);
                Message candidate = Message.text(Role.ASSISTANT, turn == null ? "" : turn.round().text());
                return await(() -> boundary.beforeStop(request.scope(), candidate))
                        .map(decision -> new Outcome(decision, candidate.text(), List.of()));
            })).doFinally(signal -> {
                try { boundary.release(request.scope()); agent.close(); terminated.complete(null); }
                catch (Throwable failure) { terminated.completeExceptionally(failure); }
            }).subscribe(result::complete, error -> {
                if (error instanceof CancellationException || closed.get()) result.complete(new Outcome(Completion.CANCELLED, "", List.of()));
                else result.completeExceptionally(error);
            });
            return result;
        }
        private List<Call> restoredPending() {
            return agent.getDelegate().getAgentState(RuntimeContext.builder().userId(request.scope().actorId()).sessionId(request.scope().conversationId()).build())
                    .getContext().stream().flatMap(msg -> msg.getContentBlocks(ToolUseBlock.class).stream())
                    .filter(tool -> tool.getState() == ToolCallState.ASKING).map(tool -> codec.decodeTypedCall(tool, catalog)).toList();
        }
        private void saveCheckpoint(CheckpointPhase phase) {
            active();
            List<Message> canonical=manifests.checkpointHistory(request.scope());
            if(canonical.isEmpty()) { if(phase==CheckpointPhase.TERMINAL||phase==CheckpointPhase.WAITING_INPUT)manifests.seal(request.scope(),request.bundle());return; }
            // Explicit public saveAgentState updates the SDK cache version, but an active
            // CallExecution keeps another loadedVersion. Snapshot a private clone instead;
            // never weaken CAS or mutate the running SDK context to force a save.
            var context=RuntimeContext.builder().userId(request.scope().actorId()).sessionId(request.scope().conversationId()).build();
            var privateCodec=new io.agentscope.core.util.JacksonJsonCodec();
            var snapshot=privateCodec.fromJson(privateCodec.toJson(agent.getDelegate().getAgentState(context)),io.agentscope.core.state.AgentState.class);
            Set<String> finished=canonical.stream().flatMap(message->message.observations().stream()).filter(o->o.status()==OperationStatus.SUCCEEDED).map(Observation::callId).collect(java.util.stream.Collectors.toSet());
            snapshot.contextMutable().clear();
            Set<String> waiting = phase == CheckpointPhase.WAITING_INPUT
                    ? (pendingCalls.get().isEmpty()?restoredPending():pendingCalls.get()).stream().map(Call::id).collect(java.util.stream.Collectors.toSet()) : Set.of();
            canonical.stream().map(codec::encode).map(message->message.withContent(message.getContent().stream().map(block->{
                if(block instanceof ToolUseBlock tool) {
                    if(finished.contains(tool.getId()))return (ContentBlock)tool.withState(ToolCallState.FINISHED);
                    if(waiting.contains(tool.getId()))return (ContentBlock)tool.withState(ToolCallState.ASKING);
                }
                return block;
            }).toList())).forEach(snapshot.contextMutable()::add);
            byte[] bytes=privateCodec.toJson(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            long version=state.load(request.scope(),"checkpoint_agent_state").version();
            if(manifests.atomicCheckpoints(request.scope())){
                manifests.commitCheckpoint(request.scope(),request.bundle(),new CheckpointDraft(version,bytes,canonical,phase));return;
            }
            if(state.compareAndSwap(request.scope(),"checkpoint_agent_state",version,bytes)<0)throw new IllegalStateException("STATE_CAS_CONFLICT");
            manifests.seal(request.scope(), request.bundle());
        }
        @Override public boolean cancel() {
            closed.set(true); boundary.cancelPending(request.scope()); pendingFutures.forEach(future -> future.cancel(true)); return run.cancel();
        }
        @Override public CompletionStage<Void> terminated() { return terminated; }
        @Override public boolean interrupt() {
            boolean interrupted = run.interrupt(); closed.set(true); boundary.cancelPending(request.scope()); pendingFutures.forEach(future -> future.cancel(true)); return interrupted;
        }
        @Override public void close() {
            if (!result.isDone()) cancel();
            if (!started.get()) {
                try { boundary.release(request.scope()); agent.close(); terminated.complete(null); }
                catch (Throwable failure) { terminated.completeExceptionally(failure); }
            }
        }

        private final class BridgeModel implements Model {
            @Override public String getModelName() { return model.modelName(request.scope()); }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> definitions, GenerateOptions options) {
                return Flux.create(sink -> {
                    AtomicBoolean emitted = new AtomicBoolean();
                    var future = model.streamTyped(request.scope(), messages.stream().map(msg -> codec.decodeTyped(msg, catalog)).toList(), List.copyOf(catalog.values()), text -> {
                        active(); emitted.set(true); sink.next(ChatResponse.builder().content(List.of(TextBlock.builder().text(text).build())).build());
                    }).toCompletableFuture();
                    pendingFutures.add(future);sink.onCancel(() -> future.cancel(true));
                    future.whenComplete((round,error) -> {
                        pendingFutures.remove(future);
                        if(error!=null){failure.compareAndSet(null,error);sink.error(error);return;}
                        try {
                            active();
                            codec.validate(round);
                            var resolved = round.calls().stream().map(call -> codec.decodeTypedCall(codec.toolUse(call), catalog)).toList();
                            Round canonical = new Round(round.text(), resolved, round.finishReason(), round.inputTokens(), round.outputTokens());
                            lastTurn.set(new Turn(UUID.randomUUID().toString(), nextTurn.getAndIncrement(), canonical));
                            sink.next(codec.response(emitted.get()?new Round("",resolved,round.finishReason(),round.inputTokens(),round.outputTokens()):canonical));
                            sink.complete();
                        } catch(Throwable rejected){failure.compareAndSet(null,rejected);sink.error(rejected);}
                    });
                });
            }
        }

        private final class CommitMiddleware implements MiddlewareBase {
            @Override public int order() { return Integer.MAX_VALUE; }
            @Override public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext context, ReasoningInput input,
                                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
                return await(() -> boundary.beforeModel(request.scope(), input.messages().stream().map(msg -> codec.decodeTyped(msg, catalog)).toList()))
                        .flatMapMany(messages -> next.apply(new ReasoningInput(messages.stream().map(codec::encode).toList(), input.tools(), input.options())))
                        .concatWith(Mono.defer(() -> {
                            Turn turn = lastTurn.get();
                            if (turn == null) return Mono.error(new IllegalStateException("MODEL_EMPTY_RESPONSE"));
                            return await(() -> boundary.afterModelCommitted(request.scope(), turn))
                                    .then(Mono.fromRunnable(()->saveCheckpoint(CheckpointPhase.MODEL_COMMITTED))).then(Mono.<AgentEvent>empty());
                        }));
            }
            @Override public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
                                                     Function<ActingInput, Flux<AgentEvent>> next) {
                return Flux.defer(() -> {
                    active();
                    Turn turn = lastTurn.get();
                    List<Call> pending = input.toolCalls().stream().map(call -> codec.decodeTypedCall(call, catalog)).toList();
                    pendingCalls.set(pending);
                    if (turn != null && !turn.round().calls().equals(pending)) return Flux.error(new IllegalStateException("TOOL_BATCH_MISMATCH"));
                    long controls = pending.stream().filter(call -> catalog.get(call.name()).category() == ToolCategory.CONTROL).count();
                    if (controls > 1 || (controls == 1 && pending.size() != 1))
                        return Flux.error(new IllegalArgumentException("CONTROL_BATCH_LIMIT"));
                    // Restored calls are checked against their canonical intent and answer before acting.
                    return await(() -> boundary.beforeControlBatch(request.scope(), pending)).thenMany(next.apply(input)).concatWith(Mono.fromRunnable(()->saveCheckpoint(CheckpointPhase.TOOL_COMMITTED)).then(Mono.<AgentEvent>empty()));
                });
            }
        }

        private final class GuardedTool extends ToolBase {
            private final ToolDefinition definition;
            GuardedTool(ToolDefinition definition) {
                super(ToolBase.builder().name(definition.name()).description(definition.description())
                        .inputSchema(definition.inputSchema())
                        .readOnly(definition.readOnly()).concurrencySafe(false));
                this.definition = definition;
            }
            @Override public Mono<PermissionDecision> checkPermissions(Map<String, Object> args, PermissionContextState context) {
                return Mono.fromSupplier(() -> {
                    active();
                    List<Call> matches = pendingCalls.get().stream().filter(call -> call.name().equals(definition.name()) && call.arguments().equals(args)).toList();
                    Decision decision = matches.size() == 1 ? decide(matches.getFirst()) : Decision.ASK;
                    return switch (decision) {
                        case ALLOW -> PermissionDecision.allow("Platform policy");
                        case ASK -> PermissionDecision.ask("Platform approval required");
                        case DENY -> PermissionDecision.deny("Platform policy denied");
                    };
                });
            }
            private Decision decide(Call call) {
                if (request.scope().planMode() && definition.category() == ToolCategory.BUSINESS) return Decision.DENY;
                return policy.decide(request.scope(), call);
            }
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.defer(() -> {
                    active();
                    Call call = codec.decodeTypedCall(param.getToolUseBlock(), catalog);
                    if (decide(call) != Decision.ALLOW) return Mono.error(new SecurityException("TOOL_POLICY_CHANGED"));
                    return await(() -> boundary.beforeTool(request.scope(), call))
                            .then(await(() -> tools.execute(request.scope(), call)))
                            .flatMap(observation -> await(() -> boundary.afterToolCommitted(request.scope(), call, observation))
                                    .thenReturn(ToolResultBlock.text(observation.text()).withIdAndName(call.id(), call.name()).withState(ToolResultState.SUCCESS)));
                }).doOnError(error -> failure.compareAndSet(null, error));
            }
        }
    }
}
