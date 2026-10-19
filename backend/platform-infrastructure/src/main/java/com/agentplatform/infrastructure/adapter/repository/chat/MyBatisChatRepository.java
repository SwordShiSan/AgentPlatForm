package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.types.Uuid7;
import com.agentplatform.types.harness.HarnessData;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.agentplatform.infrastructure.dao.chat.ChatMappers;
import com.agentplatform.infrastructure.dao.po.ChatProjections.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Facade preserving the public domain port; responsibilities live in focused adapters. */
public final class MyBatisChatRepository implements ChatRepository {
    public static final UUID BUNDLE = ChatPersistenceSupport.BUNDLE;
    private final ConversationPersistence conversations;
    private final ConfigurationPersistence configurations;
    private final DispatchPersistence dispatches;
    private final ExecutionPersistence executions;
    private final CheckpointPersistence checkpoints;
    public MyBatisChatRepository(ChatMappers mappers) {
        conversations = new ConversationPersistence(mappers);
        configurations = new ConfigurationPersistence(mappers);
        dispatches = new DispatchPersistence(mappers);
        executions = new ExecutionPersistence(mappers);
        checkpoints = new CheckpointPersistence(mappers);
    }
    @Override public List<Model> models(Actor a) { return conversations.models(a); }
    @Override public Conversation create(Actor a,UUID key,String title) { return conversations.create(a, key, title); }
    @Override public List<Conversation> conversations(Actor a,UUID before,int limit) { return conversations.conversations(a, before, limit); }
    @Override public Conversation conversation(Actor a,UUID id) { return conversations.conversation(a, id); }
    @Override public Snapshot snapshot(Actor a,UUID id) { return conversations.snapshot(a, id); }
    @Override public Task submit(Actor a,UUID convo,UUID key,UUID model,String text) { return conversations.submit(a, convo, key, model, text); }
    @Override public Task submitConfigured(Actor a,UUID convo,UUID key,UUID model,String source,String text) { return conversations.submitConfigured(a, convo, key, model, source, text); }
    @Override public Task task(Actor a,UUID id) { return conversations.task(a, id); }
    @Override public Task cancel(Actor a,UUID id,UUID key) { return conversations.cancel(a, id, key); }
    @Override public List<Event> events(Actor a,UUID id,long after) { return conversations.events(a, id, after); }
    @Override public List<Usage> usage(Actor a,UUID id) { return conversations.usage(a, id); }
    @Override public List<Publish> publications(UUID relay) { return dispatches.publications(relay); }
    @Override public void published(UUID relay,Publish item,boolean success) { dispatches.published(relay, item, success); }
    @Override public void wakeDeferred() { dispatches.wakeDeferred(); }
    @Override public Lease claim(Envelope e,UUID owner) { return dispatches.claim(e, owner); }
    @Override public void quarantine(String reason) { dispatches.quarantine(reason); }
    @Override public void check(Lease l) { executions.check(l); }
    @Override public void heartbeat(Lease l) { executions.heartbeat(l); }
    @Override public List<HarnessData.Message> history(Lease l) { return executions.history(l); }
    @Override public void delta(Lease l,String text) { executions.delta(l, text); }
    @Override public Call beginCall(Lease l,boolean real) { return executions.beginCall(l, real); }
    @Override public void startCall(Lease l,Call call,boolean real) { executions.startCall(l, call, real); }
    @Override public void settle(Lease l,Call call,HarnessData.Round round,String requestId,String error,boolean real) { executions.settle(l, call, round, requestId, error, real); }
    @Override public Credential credential(Lease l,UUID call) { return executions.credential(l, call); }
    @Override public void finish(Lease l,String status,String reason) { executions.finish(l, status, reason); }
    @Override public UUID beginTurn(Lease l) { return checkpoints.beginTurn(l); }
    @Override public void commitRound(Lease l,UUID id,HarnessData.Turn turn,ExecutionLedger.CallResult accepted) { checkpoints.commitRound(l, id, turn, accepted); }
    @Override public void startTool(Lease l,HarnessData.Call call) { checkpoints.startTool(l, call); }
    @Override public void commitTool(Lease l,HarnessData.Call call,HarnessData.Observation result) { checkpoints.commitTool(l, call, result); }
    @Override public HarnessData.StateSlot state(Lease l,String key) { return checkpoints.state(l, key); }
    @Override public long stateCas(Lease l,String key,long version,byte[] body) { return checkpoints.stateCas(l, key, version, body); }
    @Override public Set<String> stateKeys(Lease l) { return checkpoints.stateKeys(l); }
    @Override public void stateDelete(Lease l,String key) { checkpoints.stateDelete(l, key); }
    @Override public UUID sealCheckpoint(Lease l,HarnessData.Bundle bundle) { return checkpoints.sealCheckpoint(l, bundle); }
    @Override public List<HarnessData.Message> checkpointHistory(Lease l) { return checkpoints.checkpointHistory(l); }
    @Override public void validateCheckpoint(Lease l,UUID id,HarnessData.Bundle bundle) { checkpoints.validateCheckpoint(l, id, bundle); }
    @Override public void restoreCheckpoint(Lease l,UUID id,HarnessData.Bundle bundle) { checkpoints.restoreCheckpoint(l, id, bundle); }
    @Override public void recoverStopped(ExecutionLedger.StoppedOwner proof) { dispatches.recoverStopped(proof); }
    @Override public void recoverStopped(UUID owner) { dispatches.recoverStopped(owner); }
    @Override public List<Credential> recoveryCredentials() { return configurations.recoveryCredentials(); }
    @Override public UUID importCredential(Credential k) { return configurations.importCredential(k); }
    @Override public void revokeCredential(UUID id) { configurations.revokeCredential(id); }
    @Override public void bind(UUID model,Actor a,UUID credential,String source) { configurations.bind(model, a, credential, source); }
    @Override public First first(Actor a,UUID key,String title,UUID model,String source,String text) { return conversations.first(a, key, title, model, source, text); }
    @Override public Map<String,Object> preferences(Actor a) { return configurations.preferences(a); }
    @Override public Map<String,Object> preferences(Actor a,long expected,Map<String,Object> patch) { return configurations.preferences(a, expected, patch); }
    @Override public Map<String,Object> effective(Actor a) { return configurations.effective(a); }
    @Override public Map<String,Object> configuration(Actor a,UUID id) { return configurations.configuration(a, id); }
    @Override public Map<String,Object> credentialMetadata(Actor a) { return configurations.credentialMetadata(a); }
    @Override public Map<String,Object> replaceUserCredential(Actor a,long expected,Credential encrypted) { return configurations.replaceUserCredential(a, expected, encrypted); }
    @Override public String modelName(Lease l) { return configurations.modelName(l); }
    @Override public Map<String,Object> diagnostics(Actor a,UUID id) { return conversations.diagnostics(a, id); }
    @Override public boolean ready() { return conversations.ready(); }
}
