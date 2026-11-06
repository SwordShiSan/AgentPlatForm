package com.agentplatform.app.chat;

import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.infrastructure.chat.ChatCursor;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ChatService implements ChatApi {
    private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
    private final ChatRepository repository;private final ChatCursor cursor;private final com.agentplatform.infrastructure.chat.CredentialCipher encryption;
    private com.agentplatform.app.attachment.AttachmentSubmission attachmentSubmission;
    private ConfigurationResolver.Layer configurationLocks;
    public void configureAttachments(com.agentplatform.app.attachment.AttachmentSubmission submission){if(attachmentSubmission!=null)throw new IllegalStateException("Attachment composition already fixed");attachmentSubmission=java.util.Objects.requireNonNull(submission);}
    public void configureLocks(ConfigurationResolver.Layer locks){if(configurationLocks!=null)throw new IllegalStateException("Configuration locks already fixed");configurationLocks=java.util.Objects.requireNonNull(locks);}
    public ChatService(ChatRepository repository,ChatCursor cursor){this(repository,cursor,null);}
    public ChatService(ChatRepository repository,ChatCursor cursor,com.agentplatform.infrastructure.chat.CredentialCipher encryption){this.repository=repository;this.cursor=cursor;this.encryption=encryption;}
    private static UUID id(String value){try{return UUID.fromString(value);}catch(Exception invalid){throw new ChatFailure(400,"INVALID_UUID");}}
    private static Actor actor(Caller c){return new Actor(id(c.userId()),id(c.spaceId()));}
    private static ConversationView view(Conversation c){return new ConversationView(c.id().toString(),c.title(),c.version(),c.updatedAt().toString());}
    private static TaskView view(Task t){return new TaskView(t.id().toString(),t.conversationId().toString(),t.status(),t.cancelRequested(),t.reasonCode(),t.version());}
    public ConversationView first(Caller c,String key,String title,String model,String source,String text){Actor a=actor(c);var value=repository.first(a,id(key),title,model==null?null:id(model),source,text);var convo=value.conversation();var task=value.task();return new ConversationView(convo.id().toString(),convo.title(),convo.version(),convo.updatedAt().toString(),new Accepted(task.id().toString(),task.conversationId().toString(),task.status(),repository.snapshot(a,task.conversationId()).cursor()));}
    public Accepted submitConfigured(Caller c,String conversation,String key,String model,String source,String text){Actor a=actor(c);var task=repository.submitConfigured(a,id(conversation),id(key),model==null?null:id(model),source,text);return new Accepted(task.id().toString(),task.conversationId().toString(),task.status(),repository.snapshot(a,task.conversationId()).cursor());}
    public java.util.Map<String,Object> diagnostics(Caller c,String task){return repository.diagnostics(actor(c),id(task));}
    public java.util.Map<String,Object> preferences(Caller c){return repository.preferences(actor(c));}
    public java.util.Map<String,Object> preferences(Caller c,long expected,java.util.Map<String,Object> patch){
        if(configurationLocks!=null){
            checkLock(patch,"defaultModelId",configurationLocks.modelId()==null?null:configurationLocks.modelId().toString());
            checkLock(patch,"defaultCredentialSource",configurationLocks.credentialSource()==null?null:configurationLocks.credentialSource().name());
            checkLock(patch,"defaultPlanEnabled",configurationLocks.planMode());
            checkLock(patch,"defaultApprovalMode",configurationLocks.permissionMode()==null?null:configurationLocks.permissionMode().name());
        }
        return repository.preferences(actor(c),expected,patch);
    }
    private static void checkLock(java.util.Map<String,Object> patch,String field,Object locked){
        if(locked!=null&&patch.containsKey(field)&&patch.get(field)!=null&&!locked.equals(patch.get(field)))throw new ChatFailure(422,"CONFIG_CONSTRAINT_VIOLATION");
    }
    public java.util.Map<String,Object> effective(Caller c){return repository.effective(actor(c));}
    public java.util.Map<String,Object> effective(Caller c,EffectiveQuery query){
        try {
            return repository.effective(actor(c),new ConfigurationQuery(query.workspaceId()==null?null:id(query.workspaceId()),
                    query.modelId()==null?null:id(query.modelId()),query.credentialSource()==null?null:ConfigurationResolver.CredentialSource.valueOf(query.credentialSource()),
                    query.planMode(),query.permissionMode()==null?null:com.agentplatform.types.TaskPermissionData.Mode.valueOf(query.permissionMode())));
        } catch(IllegalArgumentException invalid){throw new ChatFailure(400,"INVALID_CONFIGURATION_OPTION");}
    }
    public java.util.Map<String,Object> configuration(Caller c,String task){return repository.configuration(actor(c),id(task));}
    public java.util.Map<String,Object> credentialMetadata(Caller c){return repository.credentialMetadata(actor(c));}
    public java.util.Map<String,Object> replaceCredential(Caller c,long expected,String secret){
        Actor a=actor(c);if(secret==null)return repository.replaceUserCredential(a,expected,null);
        if(secret.isBlank()||secret.length()>4096||secret.chars().anyMatch(Character::isWhitespace))throw new ChatFailure(422,"INVALID_CREDENTIAL");
        if(encryption==null)throw new ChatFailure(503,"CREDENTIAL_ENCRYPTION_UNAVAILABLE");
        return repository.replaceUserCredential(a,expected,encryption.encrypt(com.agentplatform.types.Uuid7.next(),"USER",a.spaceId(),a.userId(),secret));
    }
    public Models models(Caller c){return new Models(repository.models(actor(c)).stream().map(m->new ModelView(m.id().toString(),m.name(),List.of("TEXT"),true)).toList());}
    public ConversationView create(Caller c,String key,String title){return view(repository.create(actor(c),id(key),title));}
    public Conversations conversations(Caller c,String token,int limit){if(limit<1||limit>100)throw new ChatFailure(400,"INVALID_LIMIT");Actor a=actor(c);var values=repository.conversations(a,cursor.decode(a,token),limit);return new Conversations(values.stream().map(ChatService::view).toList(),values.size()==limit?cursor.encode(a,values.getLast().id()):null);}
    public ConversationView conversation(Caller c,String value){return view(repository.conversation(actor(c),id(value)));}
    public Accepted submit(Caller c,String conversation,String key,String model,String text){Actor a=actor(c);var task=repository.submit(a,id(conversation),id(key),id(model),text);return new Accepted(task.id().toString(),task.conversationId().toString(),task.status(),repository.snapshot(a,task.conversationId()).cursor());}
    public TaskView task(Caller c,String value){return view(repository.task(actor(c),id(value)));}
    public TaskView cancel(Caller c,String value,String key){return view(new CancellationApplicationService(repository).cancel(actor(c),id(value),id(key)));}
    public ReconciliationView reconciliation(Caller caller,String value){
        var state=new ReconciliationService(repository).read(actor(caller),id(value));
        return new ReconciliationView(state.taskId().toString(),state.stopStage(),state.stopConfirmed(),state.unknownCalls(),
                state.calls().stream().map(call->new CallReconciliationView(call.callId().toString(),call.status(),call.reservationState(),call.inputTokens(),call.outputTokens(),call.costMicros(),call.revision())).toList(),
                state.suggestedAction(),state.excludedFromContext(),state.version());
    }
    public RecoveryView recovery(Caller caller,String value){
        var state=new CheckpointRestoreService(repository).read(actor(caller),id(value));
        return new RecoveryView(state.taskId().toString(),state.eligible(),state.stage(),state.reasonCode(),state.revision(),
                state.taskVersion(),state.checkpointId()==null?null:state.checkpointId().toString(),state.traceId().toString());
    }
    private static List<java.util.Map<String,Object>> publicBlocks(Message message){
        var result=new java.util.ArrayList<java.util.Map<String,Object>>();
        for(var block:message.blocks()){
            if("TEXT".equals(block.get("type"))&&block.get("blockId") instanceof String&&block.get("text") instanceof String)result.add(java.util.Map.of("type","TEXT","blockId",block.get("blockId"),"text",block.get("text")));
            else if("FILE_REFERENCE".equals(block.get("type"))){
                var projected=new java.util.LinkedHashMap<String,Object>();
                for(String key:List.of("type","blockId","sourceKind","extractionId","name","sourceSha256","textSha256","fileId","versionId","attachmentId"))if(block.get(key) instanceof String)projected.put(key,block.get(key));
                result.add(java.util.Map.copyOf(projected));
            }
        }
        return List.copyOf(result);
    }
    public Snapshot snapshot(Caller c,String value){var snapshot=repository.snapshot(actor(c),id(value));return new Snapshot(1,snapshot.cursor(),snapshot.messages().stream().filter(m->List.of("USER","ASSISTANT").contains(m.role())).map(m->new MessageView(m.id().toString(),m.taskId().toString(),m.role(),publicBlocks(m),m.status(),m.version())).toList(),snapshot.tasks().stream().map(ChatService::view).toList(),snapshot.pending(),List.of());}
    public List<EventView> events(Caller c,String value,long after){return repository.events(actor(c),id(value),after).stream().map(e->new EventView(1,e.id().toString(),e.spaceId().toString(),e.conversationId().toString(),e.taskId()==null?null:e.taskId().toString(),e.seq(),e.type(),e.occurredAt().toString(),e.version(),e.payload())).toList();}
    public List<UsageView> usage(Caller c,String value){return repository.usage(actor(c),id(value)).stream().map(u->new UsageView(u.callId().toString(),u.status(),u.inputTokens(),u.outputTokens(),u.costMicros(),u.estimated())).toList();}

    public ConversationView firstPlan(Caller c,String key,String title,String model,String source,String text,boolean plan){
        Actor a=actor(c);var first=repository.firstPlan(a,id(key),title,model==null?null:id(model),source,text,plan);
        var conversation=first.conversation();var task=first.task();
        return new ConversationView(conversation.id().toString(),conversation.title(),conversation.version(),conversation.updatedAt().toString(),
                new Accepted(task.id().toString(),task.conversationId().toString(),task.status(),repository.snapshot(a,task.conversationId()).cursor()));
    }
    public Accepted submitPlan(Caller c,String conversation,String key,String model,String source,String text,boolean plan){
        Actor a=actor(c);var task=repository.submitPlan(a,id(conversation),id(key),model==null?null:id(model),source,text,plan);
        return new Accepted(task.id().toString(),task.conversationId().toString(),task.status(),repository.snapshot(a,task.conversationId()).cursor());
    }
    public com.agentplatform.types.PlanInteractionData.Plan plan(Caller c,String task){return repository.plan(actor(c),id(task));}
    public com.agentplatform.types.PlanInteractionData.Plan editPlan(Caller c,String task,long expected,com.agentplatform.types.PlanInteractionData.Edit edit){return repository.editPlan(actor(c),id(task),expected,edit);}
    public com.agentplatform.types.PlanInteractionData.Accepted answer(Caller c,String task,String interaction,String key,com.agentplatform.types.PlanInteractionData.Answer answer){
        return Set.of(com.agentplatform.types.PlanInteractionData.Decision.ALLOW_ONCE,com.agentplatform.types.PlanInteractionData.Decision.DENY).contains(answer.decision())
                ?new ApprovalResumeService(repository).answer(actor(c),id(task),id(interaction),id(key),answer)
                :repository.answer(actor(c),id(task),id(interaction),id(key),answer);
    }
    public List<QuotaView> quota(Caller c){return repository.quota(actor(c)).stream().map(v->new QuotaView(v.metric().name(),v.unlimited(),v.limit(),v.used(),v.reserved(),v.remaining(),v.resetAt().toString(),v.policyVersion())).toList();}

    public com.agentplatform.types.TaskPermissionData.View permissions(Caller c,String t){return new PermissionGrantService(repository).read(actor(c),id(t));}
    public com.agentplatform.types.TaskPermissionData.Scopes permissionScopes(Caller c,String t){return new PermissionGrantService(repository).scopes(actor(c),id(t));}
    public com.agentplatform.types.TaskPermissionData.Scopes permissionScopes(Caller c,String t,String cursor){return repository.permissionScopes(actor(c),id(t),cursor);}
    public com.agentplatform.types.TaskPermissionData.View permissionMode(Caller c,String t,String k,long v,com.agentplatform.types.TaskPermissionData.Mode m){return new PermissionGrantService(repository).mode(actor(c),id(t),id(k),v,m);}
    public com.agentplatform.types.TaskPermissionData.View grant(Caller c,String t,String k,long v,com.agentplatform.types.TaskPermissionData.CreateGrant g){return new PermissionGrantService(repository).grant(actor(c),id(t),id(k),v,g);}
    public com.agentplatform.types.TaskPermissionData.View revokeGrant(Caller c,String t,String g,String k,long v){return new PermissionGrantService(repository).revoke(actor(c),id(t),id(g),id(k),v);}
    public ConversationView firstAuthorized(Caller c,String k,String title,String model,String source,String text,boolean plan,String workspace,com.agentplatform.types.TaskPermissionData.Mode mode){
        Actor a=actor(c);var value=repository.firstAuthorized(a,id(k),title,model==null?null:id(model),source,text,plan,workspace==null?null:id(workspace),mode);
        var conversation=value.conversation();var task=value.task();return new ConversationView(conversation.id().toString(),conversation.title(),conversation.version(),conversation.updatedAt().toString(),new Accepted(task.id().toString(),task.conversationId().toString(),task.status(),repository.snapshot(a,task.conversationId()).cursor()));
    }
    public Accepted submitAuthorized(Caller c,String conversation,String k,String model,String source,String text,boolean plan,String workspace,com.agentplatform.types.TaskPermissionData.Mode mode){
        Actor a=actor(c);var task=repository.submitAuthorized(a,id(conversation),id(k),model==null?null:id(model),source,text,plan,workspace==null?null:id(workspace),mode);
        return new Accepted(task.id().toString(),task.conversationId().toString(),task.status(),repository.snapshot(a,task.conversationId()).cursor());
    }
    @Override public ConversationView firstAttached(Caller caller,String key,String title,String model,String source,String text,boolean plan,String workspace,com.agentplatform.types.TaskPermissionData.Mode mode,List<com.agentplatform.types.AttachmentReference> attachments){
        if(attachmentSubmission==null){if(attachments!=null&&!attachments.isEmpty())throw new ChatFailure(422,"CAPABILITY_NOT_AVAILABLE");return firstAuthorized(caller,key,title,model,source,text,plan,workspace,mode);}
        var actor=actor(caller);String body=JSON.writeValueAsString(java.util.Arrays.asList(title,model,source,text,plan,workspace,mode));
        return attachmentSubmission.submit(new com.agentplatform.domain.files.FileData.Actor(actor.userId(),actor.spaceId()),id(key),"/conversations",body,text,null,plan,workspace!=null,attachments,
                ()->firstAuthorized(caller,key,title,model,source,text,plan,workspace,mode),result->id(result.initialTask().taskId()));
    }
    @Override public Accepted submitAttached(Caller caller,String conversation,String key,String model,String source,String text,boolean plan,String workspace,com.agentplatform.types.TaskPermissionData.Mode mode,List<com.agentplatform.types.AttachmentReference> attachments){
        if(attachmentSubmission==null){if(attachments!=null&&!attachments.isEmpty())throw new ChatFailure(422,"CAPABILITY_NOT_AVAILABLE");return submitAuthorized(caller,conversation,key,model,source,text,plan,workspace,mode);}
        var actor=actor(caller);String body=JSON.writeValueAsString(java.util.Arrays.asList(model,source,text,plan,workspace,mode));
        return attachmentSubmission.submit(new com.agentplatform.domain.files.FileData.Actor(actor.userId(),actor.spaceId()),id(key),"/conversations/"+conversation+"/tasks",body,text,id(conversation),plan,workspace!=null,attachments,
                ()->submitAuthorized(caller,conversation,key,model,source,text,plan,workspace,mode),result->id(result.taskId()));
    }
    private TaskSubmissionFingerprint.Request request(String title,Submission value){
        if(value==null)throw new ChatFailure(400,"INVALID_REQUEST");
        try{return new TaskSubmissionFingerprint.Request(title,value.text(),value.workspaceId()==null?null:id(value.workspaceId()),value.modelId()==null?null:id(value.modelId()),
                value.credentialSource()==null?null:ConfigurationResolver.CredentialSource.valueOf(value.credentialSource()),value.planMode(),
                value.permissionMode()==null?null:com.agentplatform.types.TaskPermissionData.Mode.valueOf(value.permissionMode()),value.expectedEffectiveConfigHash(),value.attachments());}
        catch(IllegalArgumentException invalid){throw new ChatFailure(400,"INVALID_CONFIGURATION_OPTION");}
    }
    private static ConfigurationQuery query(TaskSubmissionFingerprint.Request r){return new ConfigurationQuery(r.workspaceId(),r.modelId(),r.credentialSource(),r.planMode(),r.permissionMode());}
    private static String legacyBody(TaskSubmissionFingerprint.Request r,boolean first){
        String source=r.credentialSource()==null?null:r.credentialSource().name();var mode=r.permissionMode()==null?com.agentplatform.types.TaskPermissionData.Mode.RISK_BASED:r.permissionMode();
        return first?JSON.writeValueAsString(java.util.Arrays.asList(r.title(),r.modelId()==null?null:r.modelId().toString(),source,r.text(),Boolean.TRUE.equals(r.planMode()),r.workspaceId()==null?null:r.workspaceId().toString(),mode)):
                JSON.writeValueAsString(java.util.Arrays.asList(r.modelId()==null?null:r.modelId().toString(),source,r.text(),Boolean.TRUE.equals(r.planMode()),r.workspaceId()==null?null:r.workspaceId().toString(),mode));
    }
    private java.util.Map<String,Object> admission(Actor actor,TaskSubmissionFingerprint.Request request){
        var preview=repository.effective(actor,query(request));
        if(request.expectedEffectiveConfigHash()!=null&&!request.expectedEffectiveConfigHash().equals(preview.get("configHash")))throw new ChatFailure(412,"EFFECTIVE_CONFIGURATION_CHANGED");
        if(!Boolean.TRUE.equals(preview.get("canSubmit")))throw new ChatFailure(422,"CONFIGURATION_NOT_AVAILABLE");
        return preview;
    }
    private ConversationView firstView(Actor a,ChatRepository.First result){var conversation=result.conversation();var task=result.task();return new ConversationView(conversation.id().toString(),conversation.title(),conversation.version(),conversation.updatedAt().toString(),new Accepted(task.id().toString(),task.conversationId().toString(),task.status(),repository.snapshot(a,task.conversationId()).cursor()));}
    private Accepted accepted(Actor a,Task task){return new Accepted(task.id().toString(),task.conversationId().toString(),task.status(),repository.snapshot(a,task.conversationId()).cursor());}
    @Override public ConversationView firstV2(Caller caller,String key,String title,Submission submission){
        Actor a=actor(caller);UUID requestId=id(key);var request=request(title,submission);String path="/conversations",body=TaskSubmissionFingerprint.compute(request),legacy=legacyBody(request,true);
        if(attachmentSubmission==null&&!request.attachments().isEmpty())throw new ChatFailure(422,"CAPABILITY_NOT_AVAILABLE");
        var prior=repository.findFirstV2(a,requestId,request);
        var fileActor=new com.agentplatform.domain.files.FileData.Actor(a.userId(),a.spaceId());
        if(prior!=null){if(attachmentSubmission!=null)attachmentSubmission.verifyReplay(fileActor,requestId,path,body,legacy,request.attachments());return firstView(a,prior);}
        var preview=admission(a,request);@SuppressWarnings("unchecked") var values=(java.util.Map<String,Object>)preview.get("values");String hash=(String)preview.get("configHash");
        if(attachmentSubmission==null)return firstView(a,repository.firstV2(a,requestId,request,hash));
        return attachmentSubmission.submit(fileActor,requestId,path,body,legacy,request.text(),null,(Boolean)values.get("planMode"),request.workspaceId()!=null,request.attachments(),
                ()->firstView(a,repository.firstV2(a,requestId,request,hash)),result->id(result.initialTask().taskId()));
    }
    @Override public Accepted submitV2(Caller caller,String conversation,String key,Submission submission){
        Actor a=actor(caller);UUID requestId=id(key),conversationId=id(conversation);var request=request(null,submission);String path="/conversations/"+conversationId+"/tasks",body=TaskSubmissionFingerprint.compute(request),legacy=legacyBody(request,false);
        if(attachmentSubmission==null&&!request.attachments().isEmpty())throw new ChatFailure(422,"CAPABILITY_NOT_AVAILABLE");
        var prior=repository.findTaskV2(a,conversationId,requestId,request);
        var fileActor=new com.agentplatform.domain.files.FileData.Actor(a.userId(),a.spaceId());
        if(prior!=null){if(attachmentSubmission!=null)attachmentSubmission.verifyReplay(fileActor,requestId,path,body,legacy,request.attachments());return accepted(a,prior);}
        var preview=admission(a,request);@SuppressWarnings("unchecked") var values=(java.util.Map<String,Object>)preview.get("values");String hash=(String)preview.get("configHash");
        if(attachmentSubmission==null)return accepted(a,repository.submitV2(a,conversationId,requestId,request,hash));
        return attachmentSubmission.submit(fileActor,requestId,path,body,legacy,request.text(),conversationId,(Boolean)values.get("planMode"),request.workspaceId()!=null,request.attachments(),
                ()->accepted(a,repository.submitV2(a,conversationId,requestId,request,hash)),result->id(result.taskId()));
    }
}
