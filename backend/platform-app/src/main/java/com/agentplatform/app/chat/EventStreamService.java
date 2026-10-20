package com.agentplatform.app.chat;

import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.api.chat.ChatEventStream;
import com.agentplatform.domain.chat.ChatFailure;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.*;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.Disposable;
import reactor.core.publisher.*;
import reactor.core.scheduler.Scheduler;
import tools.jackson.databind.json.JsonMapper;

/** Demand-driven committed replay. HTTP completion, not publisher completion, owns admission. */
public final class EventStreamService implements ChatEventStream {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ChatApi chat;
    private final EventStreamingProperties properties;
    private final Scheduler reads;
    private final Scheduler timers;
    private final Semaphore permits;

    public EventStreamService(ChatApi chat, EventStreamingProperties properties, Scheduler reads, Scheduler timers) {
        this.chat = chat; this.properties = properties; this.reads = reads; this.timers = timers;
        this.permits = new Semaphore(properties.maxConnections());
    }
    @Override public int activeStreams() { return properties.maxConnections() - permits.availablePermits(); }

    private List<ChatApi.EventView> bounded(List<ChatApi.EventView> page) {
        if (page.size() > properties.pageEvents()) throw new ChatFailure(503, "EVENT_STREAM_OVERSIZED");
        long bytes = 0;
        for (var event : page) bytes += JSON.writeValueAsString(event).getBytes(StandardCharsets.UTF_8).length + 64;
        if (bytes > properties.bufferBytes()) throw new ChatFailure(503, "EVENT_STREAM_OVERSIZED");
        return page;
    }

    @Override public Handle open(ChatApi.Caller caller, String conversation, long after, Runnable authorization) {
        if (!permits.tryAcquire()) throw new ChatFailure(503, "STREAM_CAPACITY_EXCEEDED");
        final List<ChatApi.EventView> initial;
        try { authorization.run(); initial = bounded(chat.events(caller, conversation, after)); }
        catch (RuntimeException failure) { permits.release(); throw failure; }
        var cursor = new AtomicLong(after);
        var first = new AtomicReference<>(initial);
        var stop = Sinks.<Void>empty();
        var released = new AtomicBoolean();
        var watchdog = new AtomicReference<Disposable>();
        var sourceStopped = new AtomicBoolean();
        Runnable stopSources = () -> {
            sourceStopped.set(true);
            var task = watchdog.getAndSet(null); if (task != null) task.dispose();
        };
        Flux<Frame> events = Flux.defer(() -> Mono.fromCallable(() -> {
                    authorization.run();
                    var page = first.getAndSet(null);
                    return page == null ? chat.events(caller, conversation, cursor.get()) : page;
                }).subscribeOn(reads).flatMapMany(page -> {
                    return Flux.fromIterable(bounded(page)).map(event -> { cursor.set(event.seq()); return new Frame(null, event); });
                }))
                .repeatWhen(done -> done.delayElements(Duration.ofMillis(properties.pollMillis()), timers));
        Flux<Frame> heartbeat = Flux.interval(Duration.ofMillis(properties.heartbeatMillis()), timers)
                .map(tick -> new Frame("heartbeat", null)).onBackpressureLatest();
        Flux<Frame> frames = Flux.concat(Mono.just(new Frame(" connected", null)), Flux.merge(1, events, heartbeat))
                .take(Duration.ofSeconds(properties.lifetimeSeconds()), timers)
                .takeUntilOther(stop.asMono())
                .doOnSubscribe(subscription -> {
                    var monitor = Flux.interval(Duration.ofMillis(properties.heartbeatMillis()), timers)
                            .concatMap(tick -> Mono.fromRunnable(authorization).subscribeOn(reads), 1)
                            .subscribe(ignored -> {}, failure -> stop.tryEmitError(failure));
                    watchdog.set(monitor);
                    if (sourceStopped.get()) { var task = watchdog.getAndSet(null); if (task != null) task.dispose(); }
                })
                .doFinally(signal -> stopSources.run());
        return new Handle() {
            @Override public java.util.concurrent.Flow.Publisher<Frame> frames() { return JdkFlowAdapter.publisherToFlowPublisher(frames); }
            @Override public void close() {
                stop.tryEmitEmpty(); stopSources.run();
                if (released.compareAndSet(false, true)) permits.release();
            }
        };
    }
}
