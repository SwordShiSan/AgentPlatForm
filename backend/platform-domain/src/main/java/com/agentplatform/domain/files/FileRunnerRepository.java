package com.agentplatform.domain.files;

import com.agentplatform.domain.files.FileData.*;
import com.agentplatform.domain.workspace.RunnerInstance;
import java.util.UUID;

public interface FileRunnerRepository {
    default FileCaptureData.Target captureTarget(Command command){throw new FileFailure(422,"CAPTURE_NOT_AVAILABLE");}
    default void describeCapture(Command command,UUID runner,long bytes,String hash){throw new FileFailure(422,"CAPTURE_NOT_AVAILABLE");}
    default void stopCapture(Command command,UUID runner,FileRuntime.Stop stop){throw new FileFailure(422,"CAPTURE_NOT_AVAILABLE");}
    void register(RunnerInstance instance);
    Command lookup(UUID spaceId,UUID workspaceId,UUID operationId);
    boolean accept(Command command,RunnerInstance instance,String requestHash);
    void guard(Command command,UUID runnerId);
    FileRuntime.Physical physical(Command command);
    void created(Command command,UUID runnerId,String containerId);
    void startRequested(Command command,UUID runnerId);
    void started(Command command,UUID runnerId,String startedAt);
    void exec(Command command,UUID runnerId,String execId);
    boolean priorRunnerStopped(Command command,UUID observerId);
    boolean mayObserve(Command command,UUID observerId);
    Receipt receipt(Command command);
    /** Query-only negative fact; null keeps uncertainty. Never invokes physical work. */
    Receipt observeNeverAccepted(Command command,UUID observerId,String requestHash);
    void recordResult(Command command,UUID runnerId,FileRuntime.Result result);
}
