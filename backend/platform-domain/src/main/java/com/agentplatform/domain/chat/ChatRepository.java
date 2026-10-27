package com.agentplatform.domain.chat;

import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.types.harness.HarnessData;
import java.util.List;
import java.util.UUID;

public interface ChatRepository extends ExecutionLedger, CancellationLedger, RecoveryLedger, PlanLedger, PermissionLedger, ApprovalLedger {
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
    void startCall(Lease lease, Call call, boolean real);
    void permitAcceptanceCall(Lease lease,Call call,UUID run);
    void settle(Lease lease, Call call, HarnessData.Round round, String requestId, String error, boolean real);
    Credential credential(Lease lease, UUID callId);
    void finish(Lease lease, String status, String reason);
    void recoverStopped(UUID owner);
    List<Credential> recoveryCredentials();
    UUID importCredential(Credential credential);
    void revokeCredential(UUID credential);
    void bind(UUID model, Actor actor, UUID credential, String sourcePolicy);
    record First(Conversation conversation, Task task) {}
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
    java.util.Map<String,Object> configuration(Actor actor, UUID task);
    java.util.Map<String,Object> credentialMetadata(Actor actor);
    java.util.Map<String,Object> replaceUserCredential(Actor actor, long expected, Credential encrypted);
    String modelName(Lease lease);
    boolean ready();
}
