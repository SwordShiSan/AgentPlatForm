package com.agentplatform.api.chat;

import java.util.List;
import java.util.Map;

public interface ChatApi {
    record Caller(String userId,String spaceId) {}
    record ModelView(String id,String name,List<String> capabilities,boolean available) {}
    record Models(List<ModelView> items) {}
    record ConversationView(String id,String title,long version,String updatedAt,Accepted initialTask) {
        public ConversationView(String id,String title,long version,String updatedAt){this(id,title,version,updatedAt,null);}
    }
    record Conversations(List<ConversationView> items,String nextCursor) {}
    record TaskView(String taskId,String conversationId,String status,boolean cancelRequested,String reasonCode,long version) {}
    record CallReconciliationView(String callId,String status,String reservationState,Long inputTokens,Long outputTokens,Long costMicros,long revision) {}
    record ReconciliationView(String taskId,String stopStage,boolean stopConfirmed,int unknownCalls,
                              List<CallReconciliationView> calls,String suggestedAction,boolean excludedFromContext,long version) {}
    record Accepted(String taskId,String conversationId,String status,long cursor) {}
    record RecoveryView(String taskId,boolean eligible,String stage,String reasonCode,long revision,
                        long taskVersion,String checkpointId,String traceId) {}
    record MessageView(String id,String taskId,String role,List<Map<String,Object>> blocks,String status,long version) {}
    record Snapshot(int schemaVersion,long cursor,List<MessageView> messages,List<TaskView> tasks,List<com.agentplatform.types.PlanInteractionData.Pending> pending,List<Object> artifacts) {}
    record EventView(int schemaVersion,String eventId,String spaceId,String conversationId,String taskId,long seq,String type,String occurredAt,long entityVersion,Map<String,Object> payload) {}
    record UsageView(String callId,String status,Long inputTokens,Long outputTokens,Long costMicros,boolean estimated) {}
    record QuotaView(String metric,boolean unlimited,Long limit,long used,long reserved,Long remaining,String resetAt,long policyVersion) {}
    ConversationView first(Caller caller,String key,String title,String model,String source,String text);
    Accepted submitConfigured(Caller caller,String conversation,String key,String model,String source,String text);
    ConversationView firstPlan(Caller caller,String key,String title,String model,String source,String text,boolean plan);
    Accepted submitPlan(Caller caller,String conversation,String key,String model,String source,String text,boolean plan);
    com.agentplatform.types.PlanInteractionData.Plan plan(Caller caller,String task);
    com.agentplatform.types.PlanInteractionData.Plan editPlan(Caller caller,String task,long expected,com.agentplatform.types.PlanInteractionData.Edit edit);
    com.agentplatform.types.PlanInteractionData.Accepted answer(Caller caller,String task,String interaction,String key,com.agentplatform.types.PlanInteractionData.Answer answer);
    List<QuotaView> quota(Caller caller);
    Map<String,Object> diagnostics(Caller caller,String task);
    Map<String,Object> preferences(Caller caller);
    Map<String,Object> preferences(Caller caller,long expected,Map<String,Object> patch);
    Map<String,Object> effective(Caller caller);
    Map<String,Object> configuration(Caller caller,String task);
    Map<String,Object> credentialMetadata(Caller caller);
    Map<String,Object> replaceCredential(Caller caller,long expected,String secret);
    Models models(Caller caller);
    ConversationView create(Caller caller,String key,String title);
    Conversations conversations(Caller caller,String cursor,int limit);
    ConversationView conversation(Caller caller,String id);
    Accepted submit(Caller caller,String conversation,String key,String model,String text);
    TaskView task(Caller caller,String id);
    TaskView cancel(Caller caller,String id,String key);
    ReconciliationView reconciliation(Caller caller,String id);
    RecoveryView recovery(Caller caller,String id);
    Snapshot snapshot(Caller caller,String id);
    List<EventView> events(Caller caller,String id,long after);
    List<UsageView> usage(Caller caller,String id);
}
