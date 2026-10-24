package com.agentplatform.domain.files;

import com.agentplatform.domain.files.FileData.Command;
import com.agentplatform.domain.workspace.RunnerInstance;
import java.io.InputStream;

/** Fixed trusted file operation, independent of any sandbox environment identity. */
public interface FileRuntime extends AutoCloseable {
    record Physical(String containerId,String startedAt,String execId) {}
    record Result(String state,String resultText,String stopText) {}
    record Stop(java.util.UUID commandId,String containerId,String imageId,String startedAt,String execId,
            String finishedAt,boolean neverStarted,boolean execStopped,boolean callbacksStopped,java.time.Instant observedAt) {}
    interface Authority {
        void check();
        Physical physical();
        void created(String id);
        void startRequested();
        void started(String time);
        void exec(String id);
    }
    RunnerInstance runnerInstance();
    String policyHash();
    Result apply(Command command,InputStream content,Authority authority);
    /** Observes/stops the original helper only; never executes an unknown write again. */
    Result reconcile(Command command,Authority authority,boolean priorRunnerStopped);
    @Override void close();
}
