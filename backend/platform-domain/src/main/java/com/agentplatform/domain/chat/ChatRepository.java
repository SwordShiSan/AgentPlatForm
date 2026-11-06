package com.agentplatform.domain.chat;

import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.types.harness.HarnessData;
import java.util.List;
import java.util.UUID;

public interface ChatRepository extends ExecutionLedger, CancellationLedger, RecoveryLedger, PlanLedger, PermissionLedger, ApprovalLedger, WorkspaceToolLedger {
    default List<com.agentplatform.domain.attachment.AttachmentSource> attachmentSources(Lease lease){return List.of();}
    default UUID commitAttachmentCheckpoint(Lease lease,HarnessData.Bundle bundle,HarnessData.CheckpointDraft draft,java.util.Map<UUID,String> texts){if(!texts.isEmpty())throw new ChatFailure(409,"ATTACHMENTS_NOT_CONFIGURED");return commitCheckpoint(lease,bundle,draft);}
    default HarnessData.Bundle executionBundle(Lease lease) { return ChatRuntime.bundle(); }
    List<Model> models(Actor actor);
    Conversation create(Actor actor, UUID key, String title);
    Conversation conversation(Actor actor, UUID id);
    List<Conversation> conversations(Actor actor, UUID before, int limit);
    Snapshot snapshot(Actor actor, UUID conversation);
    Task submit(Actor actor, UUID conversation, UUID key, UUID model, String text);
    Task task(Actor actor, UUID task);
    Task cancel(Actor actor, UUID task, UUID key);
    List<Event> events(Actor actor, UUID conversation, long after);
    List<Usage> usage(Actor actor, UUID task);
    List<Publish> publications(UUID relay);
    void published(UUID relay, Publish item, boolean success);
    void wakeDeferred();
    Lease claim(Envelope message, UUID owner);
    void quarantine(String reason);
    void heartbeat(Lease lease);
    void check(Lease lease);
    List<HarnessData.Message> history(Lease lease);
    void delta(Lease lease, String text);
    Call beginCall(Lease lease, boolean real);
    default ContextPolicy contextPolicy(Lease lease) { return ContextPolicy.legacy(); }
    default Call beginCall(Lease lease, boolean real, long estimatedInput) {
        if (!"legacy-v1".equals(contextPolicy(lease).version()))
            throw new ChatFailure(503, "CONTEXT_RESERVATION_NOT_IMPLEMENTED");
        return beginCall(lease, real);
    }
    void startCall(Lease lease, Call call, boolean real);
    void permitAcceptanceCall(Lease lease,Call call,UUID run);
    void settle(Lease lease, Call call, HarnessData.Round round, String requestId, String error, boolean real);
    default boolean settleOutcome(Lease lease, Call call, HarnessData.Round round, String requestId, String error, boolean real) { settle(lease,call,round,requestId,error,real); return true; }
    Credential credential(Lease lease, UUID callId);
    void finish(Lease lease, String status, String reason);
    void recoverStopped(UUID owner);
    List<Credential> recoveryCredentials();
    UUID importCredential(Credential credential);
    void revokeCredential(UUID credential);
    void bind(UUID model, Actor actor, UUID credential, String sourcePolicy);
    record First(Conversation conversation, Task task) {}
    default First findFirstV2(Actor actor,UUID key,TaskSubmissionFingerprint.Request request){throw new ChatFailure(503,"CONFIGURATION_NOT_AVAILABLE");}
    default Task findTaskV2(Actor actor,UUID conversation,UUID key,TaskSubmissionFingerprint.Request request){throw new ChatFailure(503,"CONFIGURATION_NOT_AVAILABLE");}
    default First firstV2(Actor actor,UUID key,TaskSubmissionFingerprint.Request request){throw new ChatFailure(503,"CONFIGURATION_NOT_AVAILABLE");}
    default Task submitV2(Actor actor,UUID conversation,UUID key,TaskSubmissionFingerprint.Request request){throw new ChatFailure(503,"CONFIGURATION_NOT_AVAILABLE");}
    default First firstV2(Actor actor,UUID key,TaskSubmissionFingerprint.Request request,String admissionHash){throw new ChatFailure(503,"CONFIGURATION_NOT_AVAILABLE");}
    default Task submitV2(Actor actor,UUID conversation,UUID key,TaskSubmissionFingerprint.Request request,String admissionHash){throw new ChatFailure(503,"CONFIGURATION_NOT_AVAILABLE");}
    First first(Actor actor, UUID key, String title, UUID model, String source, String text);
    Task submitConfigured(Actor actor, UUID conversation, UUID key, UUID model, String source, String text);
    First firstPlan(Actor actor, UUID key, String title, UUID model, String source, String text, boolean planMode);
    Task submitPlan(Actor actor, UUID conversation, UUID key, UUID model, String source, String text, boolean planMode);
    List<QuotaData.View> quota(Actor actor);
    long configureQuota(QuotaData.Change change);
    java.util.Map<String,Object> diagnostics(Actor actor, UUID task);
    java.util.Map<String,Object> preferences(Actor actor);
    java.util.Map<String,Object> preferences(Actor actor, long expected, java.util.Map<String,Object> patch);
    java.util.Map<String,Object> effective(Actor actor);
    default java.util.Map<String,Object> effective(Actor actor, ConfigurationQuery query) { throw new ChatFailure(503,"CONFIGURATION_NOT_AVAILABLE"); }
    java.util.Map<String,Object> configuration(Actor actor, UUID task);
    java.util.Map<String,Object> credentialMetadata(Actor actor);
    java.util.Map<String,Object> replaceUserCredential(Actor actor, long expected, Credential encrypted);
    String modelName(Lease lease);
    boolean ready();
}
