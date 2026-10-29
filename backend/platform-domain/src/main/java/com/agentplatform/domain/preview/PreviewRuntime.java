package com.agentplatform.domain.preview;

import java.nio.file.Path;
import com.agentplatform.domain.workspace.RunnerInstance;

/** Physical rendering only. Object transfer, durable admission and settlement stay outside this port. */
public interface PreviewRuntime extends AutoCloseable {
    record Physical(String containerId, String startedAt) {}
    record Result(String state, String format, String mediaType, long bytes, String sha256,
            Integer pages, Path output, boolean sourceReferenced, String errorCode, boolean retryable,
            PreviewData.Stop stop) {}
    interface Guard {
        void check();
        Physical physical();
        /** Defaults conservatively for old probe adapters: missing evidence never proves no start. */
        default boolean startWasRequested(){return true;}
        /** Append-only exact Docker observation; never authorizes a start or a new conversion. */
        default void observed(Physical physical,boolean startRequested){throw new IllegalStateException("Physical observation adapter required");}
        /** Record the fixed read exec identity before starting its transport. */
        default void execCreated(String execId){throw new IllegalStateException("Preview output read adapter required");}
        void created(String containerId);
        void startRequested();
        void started(String startedAt);
    }
    String policyHash();
    RunnerInstance runnerInstance();
    Result render(PreviewData.Command command, Path source, Path outputDirectory, Guard authority);
    /** Inspect/stop the original invocation. This method must never execute another conversion. */
    Result reconcile(PreviewData.Command command, Path outputDirectory, Guard authority);
    /** Called only after the immutable exact stop receipt commits. */
    void retire(PreviewData.Command command, PreviewData.Stop stop);
    @Override void close();
}
