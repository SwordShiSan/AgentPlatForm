package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.ChatData.Actor;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.domain.chat.ContextPolicy;
import com.agentplatform.domain.chat.ContextProjectionStore;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.infrastructure.harness.agentscope.AgentScopeContextAdmission;
import com.agentplatform.types.harness.HarnessData.Bundle;
import com.agentplatform.types.harness.HarnessData.Message;
import com.agentplatform.types.harness.HarnessData.Role;
import com.agentplatform.types.harness.HarnessData.ToolDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Deterministic, extractive context projection. Canonical messages and tool facts remain untouched. */
public final class ContextProjectionService {
    private static final System.Logger LOGGER=System.getLogger(ContextProjectionService.class.getName());
    private final ContextProjectionStore store;
    private final JsonMapper json;
    public ContextProjectionService(ContextProjectionStore store, JsonMapper json) { this.store=store; this.json=json; }

    public List<Message> project(Lease lease, Bundle bundle, List<Message> canonical,
                                 List<ToolDefinition> definitions, ContextPolicy policy) {
        long limit=policy.inputLimit();
        long estimate=AgentScopeContextAdmission.estimate(canonical,definitions);
        if(estimate<=limit*70/100)return List.copyOf(canonical);
        String watermark=hash(write(canonical));
        String sources=sourceManifest(canonical);
        var existing=store.current(lease,bundle.hash(),watermark,sources);
        if(existing.isPresent()) {
            var projection=existing.get();
            if(hash(write(projection.messages())).equals(projection.summarySha256())) {
                AgentScopeContextAdmission.requireFits(projection.messages(),definitions,policy);
                return projection.messages();
            }
            throw new ChatFailure(409,"CONTEXT_PROJECTION_HASH_MISMATCH");
        }
        // Select the recent-message boundary from canonical history before deduplication.
        // Deduplicating repeated short replies first can shift a large older message
        // into the protected recent window and prevent the required projection.
        boolean compactOlder=estimate>limit*80/100;
        List<Message> reduced=compactOlder?compactOlderAssistantText(canonical):List.copyOf(canonical);
        boolean compacted=!reduced.equals(canonical);
        reduced=deduplicateOptionalAssistantText(reduced);
        long afterDedup=AgentScopeContextAdmission.estimate(reduced,definitions);
        if(afterDedup<=limit*70/100&&!compacted)return List.copyOf(reduced);
        if(reduced.equals(canonical)&&estimate<=limit)return List.copyOf(canonical);
        long result=AgentScopeContextAdmission.estimate(reduced,definitions);
        if(result>limit)throw new ChatFailure(422,"CONTEXT_REQUIRED_TOO_LARGE");
        // The platform records only the committed projection; failed CAS leaves the prior projection authoritative.
        var saved=store.saveIfWatermarkCurrent(lease,bundle,watermark,sources,hash(write(reduced)),reduced,result,policy.version());
        if(hash(write(saved.messages())).equals(saved.summarySha256()))return saved.messages();
        throw new ChatFailure(409,"CONTEXT_PROJECTION_HASH_MISMATCH");
    }

    /**
     * Performs the same deterministic, non-persistent projection used at the
     * worker safe point. Submission admission calls this before accepting the
     * next task so compressible history is not rejected before it can reach
     * that safe point. It never mutates canonical messages or writes a summary.
     */
    public static List<Message> preview(List<Message> canonical,List<ToolDefinition> definitions,ContextPolicy policy) {
        long limit=policy.inputLimit();
        long estimate=AgentScopeContextAdmission.estimate(canonical,definitions);
        if(estimate<=limit*70/100)return List.copyOf(canonical);
        // Preserve the recency boundary from canonical history; optional duplicate
        // removal must not promote old oversized assistant prose into the recent window.
        List<Message> reduced=estimate>limit*80/100?compactOlderAssistantText(canonical):List.copyOf(canonical);
        reduced=deduplicateOptionalAssistantText(reduced);
        long afterDedup=AgentScopeContextAdmission.estimate(reduced,definitions);
        if(afterDedup<=limit*70/100)return List.copyOf(reduced);
        if(reduced.equals(canonical)&&estimate<=limit)return List.copyOf(canonical);
        long projected=AgentScopeContextAdmission.estimate(reduced,definitions);
        if(projected>limit){
            LOGGER.log(System.Logger.Level.WARNING,
                    "Context preview remains over limit: before={0}, afterDedup={1}, afterProjection={2}, limit={3}",
                    estimate,afterDedup,projected,limit);
            throw new ChatFailure(422,"CONTEXT_REQUIRED_TOO_LARGE");
        }
        return List.copyOf(reduced);
    }

    public ContextProjectionStore.Summary describe(Actor actor, UUID task) { return store.describe(actor,task); }

    private static List<Message> deduplicateOptionalAssistantText(List<Message> history) {
        var result=new ArrayList<Message>(history.size());Set<String> seen=new HashSet<>();
        for(Message message:history) {
            if(message.role()==Role.ASSISTANT&&message.calls().isEmpty()&&message.observations().isEmpty()&&!reference(message)) {
                String key=message.text().strip().replaceAll("\\s+"," ");
                if(!key.isEmpty()&&!seen.add(key))continue;
            }
            result.add(message);
        }
        return List.copyOf(result);
    }

    private static List<Message> compactOlderAssistantText(List<Message> history) {
        int firstSystem=history.size()>0&&history.getFirst().role()==Role.SYSTEM?1:0;
        int recentStart=Math.max(firstSystem,history.size()-8);
        var retained=new ArrayList<Message>(history.size());var excerpts=new ArrayList<String>();
        for(int i=0;i<history.size();i++) {
            Message message=history.get(i);
            if(i>=recentStart||message.role()!=Role.ASSISTANT||!message.calls().isEmpty()||!message.observations().isEmpty()||reference(message)) {
                retained.add(message);continue;
            }
            String text=message.text().strip().replaceAll("\\s+"," ");
            if(text.isEmpty())continue;
            excerpts.add("- Earlier assistant response (source message "+i+"): "+excerpt(text));
        }
        if(excerpts.isEmpty())return List.copyOf(history);
        int insertAt=firstSystem;
        String summary="[Platform context summary, untrusted historical assistant prose; user messages, attachment references, approvals, and complete tool-call/result pairs are retained verbatim. Omitted assistant wording is not a new fact.]\n"
                +String.join("\n",excerpts);
        retained.add(insertAt,Message.text(Role.SYSTEM,summary));
        return List.copyOf(retained);
    }

    private static String excerpt(String value) {
        if(value.length()<=1200)return value;
        return value.substring(0,560)+" … [middle omitted in the derived summary] … "+value.substring(value.length()-560);
    }
    private String sourceManifest(List<Message> messages) {
        var sourceText=new ArrayList<String>();
        for(Message message:messages)if(message.text().contains("attachment")||message.text().contains("FILE_REFERENCE")||message.text().contains("[file")||reference(message))sourceText.add(message.role()+":"+message.text());
        return hash(write(sourceText));
    }
    private static boolean reference(Message message){return message.text().startsWith("[PLATFORM_REFERENCE ")||message.text().startsWith("[PLATFORM_CONTEXT_DEGRADED ");}
    private String write(Object value) { try{return json.writeValueAsString(value);}catch(Exception failure){throw new ChatFailure(503,"CONTEXT_PROJECTION_SERIALIZATION_FAILED");} }
    private static String hash(String value) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);} }
}
