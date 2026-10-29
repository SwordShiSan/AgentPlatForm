package com.agentplatform.domain.preview;

import com.agentplatform.domain.files.FileData.Actor;
import com.agentplatform.domain.preview.PreviewData.*;
import java.util.List;
import java.util.UUID;

/** Short transactions only. Renderer/transport operations always occur outside these calls. */
public interface PreviewRepository {
    Job create(Actor actor,UUID file,UUID version,String format,UUID request,String requestHash);
    Job get(Actor actor,UUID preview);
    Job retry(Actor actor,UUID preview,long expected,UUID request,String requestHash);
    List<Publication> publications(UUID relayOwner);
    void published(Publication publication,boolean confirmed);
    Command claim(Publication publication,UUID owner);
    void renew(Command command);
    void guard(Command command);
    void beforeCall(Command command);
    void complete(Command command,Receipt receipt);
    void unknown(Command command,String errorCode);
    void blockExpired();
    List<Command> reconciliations();
    void quarantine(UUID id,String hash);
    /** True only for the first durable handoff to this exact runner process. */
    boolean runnerClaim(Command command,UUID runnerId);
    boolean runnerObserveNeverAccepted(Command command,UUID observerId);
    Command runnerLookup(UUID previewId);
    PreviewRuntime.Physical runnerPhysical(Command command,UUID runnerId);
    boolean runnerStartWasRequested(Command command,UUID runnerId);
    void runnerCreated(Command command,UUID runnerId,String containerId);
    void runnerStartRequested(Command command,UUID runnerId);
    void runnerStarted(Command command,UUID runnerId,String startedAt);
    /** Append an inspected original container only; this never admits start or execution. */
    void runnerObservedPhysical(Command command,UUID runnerId,String containerId,String startedAt,boolean startRequested);
    void runnerExecCreated(Command command,UUID runnerId,String execId);
    void runnerGuard(Command command,UUID runnerId);
    void runnerReceipt(Command command,UUID runnerId,Receipt receipt);
    Receipt receipt(Command command);
    /** Called only after the source stream and its callback have actually closed. */
    void recordSourceTransferClosed(Command command,UUID runnerId);
}
