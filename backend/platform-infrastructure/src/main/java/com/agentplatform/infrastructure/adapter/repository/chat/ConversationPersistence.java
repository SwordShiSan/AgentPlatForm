package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatRepository.First;
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

/** Conversation persistence use cases over typed MyBatis collaborators. */
final class ConversationPersistence extends ChatPersistenceSupport {
    private final boolean workspaceTools;
    private final ContextPolicy contextPolicy;
    private final com.agentplatform.infrastructure.dao.chat.WorkspaceConfigurationMapper workspaceConfiguration;
    private final com.agentplatform.infrastructure.dao.chat.SubmissionIdentityMapper submissionIdentity;
    private final ConfigurationResolver.Layer locks;
    private final boolean planCapability,permissionsCapability;
    ConversationPersistence(ChatMappers mappers) { this(mappers,false,ContextPolicy.legacy()); }
    ConversationPersistence(ChatMappers mappers,boolean workspaceTools) { this(mappers,workspaceTools,ContextPolicy.legacy()); }
    ConversationPersistence(ChatMappers mappers,boolean workspaceTools,ContextPolicy contextPolicy) { this(mappers,workspaceTools,contextPolicy,null,null,null,true,true); }
    ConversationPersistence(ChatMappers mappers,boolean workspaceTools,ContextPolicy contextPolicy,
            com.agentplatform.infrastructure.dao.chat.WorkspaceConfigurationMapper workspaceConfiguration,
            com.agentplatform.infrastructure.dao.chat.SubmissionIdentityMapper submissionIdentity,
            ConfigurationResolver.Layer locks,boolean planCapability,boolean permissionsCapability) {
        super(mappers);this.workspaceTools=workspaceTools;this.contextPolicy=Objects.requireNonNull(contextPolicy);
        this.workspaceConfiguration=workspaceConfiguration;this.submissionIdentity=submissionIdentity;this.locks=locks;
        this.planCapability=planCapability;this.permissionsCapability=permissionsCapability;
    }

    public List<Model> models(Actor a){return tx(c->{auth(c,a);List<Model> result=new ArrayList<>();{ var rows67 = c.configuration().selectModelDefinition67(a.spaceId(), a.userId(), a.spaceId(), a.userId()); for (var row67 : rows67)result.add(new Model(row67.value1(),row67.value2(),row67.value3())); }return List.copyOf(result);});}
    public Conversation create(Actor a,UUID key,String title){return tx(c->{
        auth(c,a);String name=title==null?"普通对话":title;if(name.isBlank()||name.codePointCount(0,name.length())>120)throw new ChatFailure(400,"INVALID_TITLE");
        String fingerprint=hash(name);UUID id=idempotency(c,a,key,"/conversations",fingerprint);
        if(id==null){id=Uuid7.next();c.conversation().insertConversation78(id, a.spaceId(), a.userId(), name);remember(c,a,key,"/conversations",fingerprint,id);}
        { var rows66 = c.conversation().selectConversation66(a.spaceId(), id); var row66 = rows66.isEmpty() ? null : rows66.getFirst(); return readConversation(row66); }
    });}
    public List<Conversation> conversations(Actor a,UUID before,int limit){return tx(c->{auth(c,a);List<Conversation> values=new ArrayList<>();{ var rows65 = c.conversation().selectConversation65(a.spaceId(), before, a.spaceId(), before, Math.min(100,Math.max(1,limit))); for (var row65 : rows65)values.add(readConversation(row65)); }return List.copyOf(values);});}
    public Conversation conversation(Actor a,UUID id){return tx(c->{auth(c,a);{ var rows64 = c.conversation().selectConversation64(a.spaceId(), id); var row64 = rows64.isEmpty() ? null : rows64.getFirst(); if(row64 == null)throw new ChatFailure(404,"RESOURCE_NOT_FOUND");return readConversation(row64); }});}
    public Snapshot snapshot(Actor a,UUID id){return tx(true,c->{
        auth(c,a);conversation(c,a,id,false);long cursor;
        { var rows63 = c.conversation().selectConversation63(a.spaceId(), id); var row63 = rows63.isEmpty() ? null : rows63.getFirst(); cursor=row63.value1(); }
        List<Message> messages=new ArrayList<>();List<Task> tasks=new ArrayList<>();
        { var rows62 = c.conversation().selectMessage62(a.spaceId(), id); for (var row62 : rows62)messages.add(new Message(row62.id(),row62.taskId(),row62.role(),blocks(row62.blocksJson()),row62.status(),row62.version())); }
        { var rows61 = c.conversation().selectTask61(a.spaceId(), id); for (var row61 : rows61)tasks.add(readTask(row61)); }
        return new Snapshot(id,cursor,List.copyOf(messages),List.copyOf(tasks),new PlanPersistence(c).pending(a,id));
    });}
    public Task submit(Actor a,UUID convo,UUID key,UUID model,String text){return tx(c->{String source=null;{ var rows60 = c.configuration().selectModelBinding60(a.spaceId(), a.userId(), model); var row60 = rows60.isEmpty() ? null : rows60.getFirst(); if(row60 != null)source=row60.value1(); }return submit(c,a,convo,key,model,source,text,false,null,com.agentplatform.types.TaskPermissionData.Mode.RISK_BASED,false,contextPolicy);});}
    public Task submitConfigured(Actor a,UUID convo,UUID key,UUID model,String source,String text){return tx(c->submit(c,a,convo,key,model,source,text,false,null,com.agentplatform.types.TaskPermissionData.Mode.RISK_BASED,false,contextPolicy));}
    public Task task(Actor a,UUID id){return tx(c->{auth(c,a);return task(c,a,id,false);});}
    public Task cancel(Actor a,UUID id,UUID key){return tx(c->{
        auth(c,a);Task t=task(c,a,id,false);new MyBatisQuotaLedger(c.quota()).lock(a.userId());c.dispatch().updateExecutionSlot87();conversation(c,a,t.conversationId(),true);t=task(c,a,id,true);
        String path="/tasks/"+id+"/cancel";UUID existing=idempotency(c,a,key,path,hash("cancel"));if(existing!=null)return t;
        if(t.cancelRequested()||!Set.of("QUEUED","RUNNING","WAITING_INPUT","BLOCKED").contains(t.status())){remember(c,a,key,path,hash("cancel"),id);return t;}
        c.control().recordCancel(a,id,key);
        if("WAITING_INPUT".equals(t.status())){
            if(c.control().latestStopCount(id)!=1||c.recovery().live(id)>0)throw new ChatFailure(409,"EXECUTION_STOP_NOT_CONFIRMED");
            new PlanPersistence(c).closeInteractions(a,id,"CANCELLED");c.conversation().updateTask91(id);c.conversation().updateTask135("CANCELLED","TASK_CANCEL_REQUESTED",id);
            c.dispatch().updateExecutionDispatch89(id);releaseRemaining(c,a,id);c.conversation().updateMessage90(id);assistantTerminalEvent(c,a,t.conversationId(),id);
        }else if("QUEUED".equals(t.status())){c.conversation().updateTask88(id);c.dispatch().updateExecutionDispatch89(id);releaseRemaining(c,a,id);c.conversation().updateMessage90(id);assistantTerminalEvent(c,a,t.conversationId(),id);}
        else if(Set.of("RUNNING","BLOCKED").contains(t.status())) {
            c.conversation().updateTask91(id);
            if("BLOCKED".equals(t.status())&&!c.control().taskState(id).live()&&c.control().latestStopCount(id)==1&&c.recovery().unknown(id)==0)
                c.conversation().updateTask135("CANCELLED","TASK_CANCEL_REQUESTED",id);
        }
        remember(c,a,key,path,hash("cancel"),id);Task now=task(c,a,id,false);event(c,a,t.conversationId(),id,"task.state_changed",now.version(),Map.of("taskId",id.toString(),"status",now.status(),"cancelRequested",now.cancelRequested()));return now;
    });}
    public List<Event> events(Actor a,UUID id,long after){return tx(c->{
        auth(c,a);conversation(c,a,id,false);
        var bounds=c.event().bounds(a.spaceId(),id);
        if(after<0||after>bounds.current())throw new ChatFailure(409,"INVALID_CURSOR");
        if(after<bounds.prunedThrough())throw new ChatFailure(410,"CURSOR_EXPIRED");
        List<Event> result=new ArrayList<>();{ var rows51 = c.event().page(a.spaceId(), id, after);
            for (var row51 : rows51)result.add(new Event(row51.id(),a.spaceId(),id,row51.taskId(),row51.seq(),row51.type(),row51.entityVersion(),row51.createdAt(),object(row51.payloadJson())));
         }return List.copyOf(result);
    });}
    public List<Usage> usage(Actor a,UUID id){return tx(c->{auth(c,a);task(c,a,id,false);List<Usage> result=new ArrayList<>();{ var rows50 = c.accounting().selectModelCall50(a.spaceId(), id); for (var row50 : rows50)result.add(new Usage(row50.id(),row50.status(),row50.inputTokens(),row50.outputTokens(),row50.costMicros(),row50.estimated())); }return List.copyOf(result);});}
    public First first(Actor a,UUID key,String title,UUID model,String source,String text){return firstPlan(a,key,title,model,source,text,false);}
    public Task submitPlan(Actor a,UUID convo,UUID key,UUID model,String source,String text,boolean plan){return tx(c->submit(c,a,convo,key,model,source,text,plan,null,com.agentplatform.types.TaskPermissionData.Mode.RISK_BASED,false,contextPolicy));}
    public Task submitAuthorized(Actor a,UUID convo,UUID key,UUID model,String source,String text,boolean plan,UUID workspace,com.agentplatform.types.TaskPermissionData.Mode mode){return tx(c->submit(c,a,convo,key,model,source,text,plan,workspace,mode,workspaceTools,contextPolicy));}
    public First firstPlan(Actor a,UUID key,String title,UUID model,String source,String text,boolean plan){return firstAuthorized(a,key,title,model,source,text,plan,null,com.agentplatform.types.TaskPermissionData.Mode.RISK_BASED);}
    public First firstAuthorized(Actor a,UUID key,String title,UUID model,String source,String text,boolean plan,UUID workspace,com.agentplatform.types.TaskPermissionData.Mode mode){return tx(c->{
        auth(c,a);String name=title==null?"新对话":title;if(name.isBlank()||name.codePointCount(0,name.length())>120)throw new ChatFailure(400,"INVALID_TITLE");
        String fingerprint=hash(json(Arrays.asList(name,model,source,text)));if(plan)fingerprint=hash(fingerprint+"\nplan=true");if(workspace!=null||mode!=com.agentplatform.types.TaskPermissionData.Mode.RISK_BASED)fingerprint=hash(fingerprint+"\nworkspace="+workspace+"\nmode="+mode);UUID existing=idempotency(c,a,key,"/conversations",fingerprint);
        if(existing!=null){Conversation convo;{ var rows18 = c.conversation().selectConversation18(a.spaceId(), existing); var row18 = rows18.isEmpty() ? null : rows18.getFirst(); if(row18 == null)throw new ChatFailure(404,"RESOURCE_NOT_FOUND");convo=readConversation(row18); }{ var rows17 = c.conversation().selectTask17(existing); var row17 = rows17.isEmpty() ? null : rows17.getFirst(); if(row17 == null)throw new ChatFailure(409,"IDEMPOTENCY_CONFLICT");return new First(convo,readTask(row17)); }}
        UUID id=Uuid7.next();c.conversation().insertConversation164(id, a.spaceId(), a.userId(), name);
        Task task=submit(c,a,id,key,model,source,text,plan,workspace,mode,workspaceTools,contextPolicy);remember(c,a,key,"/conversations",fingerprint,id);
        { var rows16 = c.conversation().selectConversation16(id); var row16 = rows16.isEmpty() ? null : rows16.getFirst(); return new First(readConversation(row16),task); }
    });}

    private void requireV2(){if(workspaceConfiguration==null||submissionIdentity==null)throw new ChatFailure(503,"CONFIGURATION_NOT_AVAILABLE");}
    private static String legacyTask(TaskSubmissionFingerprint.Request r){
        String source=r.credentialSource()==null?null:r.credentialSource().name();
        String value=hash(source==null?r.modelId()+"\n"+r.text():r.modelId()+"\n"+source+"\n"+r.text());
        if(Boolean.TRUE.equals(r.planMode()))value=hash(value+"\nplan=true");
        var mode=r.permissionMode()==null?com.agentplatform.types.TaskPermissionData.Mode.RISK_BASED:r.permissionMode();
        if(r.workspaceId()!=null||mode!=com.agentplatform.types.TaskPermissionData.Mode.RISK_BASED)value=hash(value+"\nworkspace="+r.workspaceId()+"\nmode="+mode);
        return value;
    }
    private static String legacyFirst(TaskSubmissionFingerprint.Request r){
        String name=r.title()==null?"新对话":r.title();String source=r.credentialSource()==null?null:r.credentialSource().name();
        String value=hash(json(Arrays.asList(name,r.modelId(),source,r.text())));
        if(Boolean.TRUE.equals(r.planMode()))value=hash(value+"\nplan=true");
        var mode=r.permissionMode()==null?com.agentplatform.types.TaskPermissionData.Mode.RISK_BASED:r.permissionMode();
        if(r.workspaceId()!=null||mode!=com.agentplatform.types.TaskPermissionData.Mode.RISK_BASED)value=hash(value+"\nworkspace="+r.workspaceId()+"\nmode="+mode);
        return value;
    }
    private First loadFirst(ChatMappers c,Actor a,UUID id){
        var conversations=c.conversation().selectConversation18(a.spaceId(),id);if(conversations.isEmpty())throw new ChatFailure(404,"RESOURCE_NOT_FOUND");
        var tasks=c.conversation().selectTask17(id);if(tasks.isEmpty())throw new ChatFailure(409,"IDEMPOTENCY_CONFLICT");
        return new First(readConversation(conversations.getFirst()),readTask(tasks.getFirst()));
    }
    public First findFirstV2(Actor a,UUID key,TaskSubmissionFingerprint.Request request){return tx(c->{
        requireV2();auth(c,a);var existing=new SubmissionIdentityRepository(submissionIdentity).lookup(a,"/conversations",key,legacyFirst(request),TaskSubmissionFingerprint.compute(request));
        return existing==null?null:loadFirst(c,a,existing);
    });}
    public Task findTaskV2(Actor a,UUID conversation,UUID key,TaskSubmissionFingerprint.Request request){return tx(c->{
        requireV2();auth(c,a);String path="/conversations/"+conversation+"/tasks";
        var existing=new SubmissionIdentityRepository(submissionIdentity).lookup(a,path,key,legacyTask(request),TaskSubmissionFingerprint.compute(request));
        return existing==null?null:task(c,a,existing,false);
    });}
    private Map<String,Object> resolveV2(ChatMappers c,Actor a,TaskSubmissionFingerprint.Request request,String admissionHash){
        var query=new ConfigurationQuery(request.workspaceId(),request.modelId(),request.credentialSource(),request.planMode(),request.permissionMode());
        var preview=new ConfigurationPersistence(c,contextPolicy).effective(a,query,workspaceConfiguration,locks,planCapability,permissionsCapability);
        if(request.expectedEffectiveConfigHash()!=null&&!request.expectedEffectiveConfigHash().equals(preview.get("configHash")))throw new ChatFailure(412,"EFFECTIVE_CONFIGURATION_CHANGED");
        if(admissionHash!=null&&!admissionHash.equals(preview.get("configHash")))throw new ChatFailure(412,"EFFECTIVE_CONFIGURATION_CHANGED");
        if(!Boolean.TRUE.equals(preview.get("canSubmit")))throw new ChatFailure(422,"CONFIGURATION_NOT_AVAILABLE");
        return preview;
    }
    @SuppressWarnings("unchecked") private Task submitResolved(ChatMappers c,Actor a,UUID conversation,UUID key,TaskSubmissionFingerprint.Request request,Map<String,Object> preview){
        var values=(Map<String,Object>)preview.get("values");
        var metadata=new LinkedHashMap<String,Object>();metadata.put("resolverVersion",ResolvedTaskConfiguration.RESOLVER_VERSION);metadata.put("effectiveHash",preview.get("configHash"));metadata.put("sources",preview.get("sources"));metadata.put("lockedFields",preview.get("lockedFields"));metadata.put("preferenceRevision",preview.get("preferenceRevision"));metadata.put("workspaceRevision",preview.get("workspaceRevision"));
        return submit(c,a,conversation,key,UUID.fromString((String)values.get("modelId")),(String)values.get("credentialSource"),request.text(),(Boolean)values.get("planMode"),request.workspaceId(),com.agentplatform.types.TaskPermissionData.Mode.valueOf((String)values.get("permissionMode")),workspaceTools,contextPolicy,TaskSubmissionFingerprint.compute(request),metadata,submissionIdentity);
    }
    public First firstV2(Actor a,UUID key,TaskSubmissionFingerprint.Request request){return firstV2(a,key,request,null);}
    public First firstV2(Actor a,UUID key,TaskSubmissionFingerprint.Request request,String admissionHash){return tx(c->{
        requireV2();auth(c,a);String name=request.title()==null?"新对话":request.title();if(name.isBlank()||name.codePointCount(0,name.length())>120)throw new ChatFailure(400,"INVALID_TITLE");
        String requestHash=TaskSubmissionFingerprint.compute(request);var identities=new SubmissionIdentityRepository(submissionIdentity);
        UUID existing=identities.lookup(a,"/conversations",key,legacyFirst(request),requestHash);if(existing!=null)return loadFirst(c,a,existing);
        var preview=resolveV2(c,a,request,admissionHash);UUID id=Uuid7.next();c.conversation().insertConversation164(id,a.spaceId(),a.userId(),name);
        Task task=submitResolved(c,a,id,key,request,preview);identities.remember(a,"/conversations",key,requestHash,id);
        var row=c.conversation().selectConversation16(id);return new First(readConversation(row.getFirst()),task);
    });}
    public Task submitV2(Actor a,UUID conversation,UUID key,TaskSubmissionFingerprint.Request request){return submitV2(a,conversation,key,request,null);}
    public Task submitV2(Actor a,UUID conversation,UUID key,TaskSubmissionFingerprint.Request request,String admissionHash){return tx(c->{
        requireV2();auth(c,a);String path="/conversations/"+conversation+"/tasks";String requestHash=TaskSubmissionFingerprint.compute(request);
        UUID existing=new SubmissionIdentityRepository(submissionIdentity).lookup(a,path,key,legacyTask(request),requestHash);if(existing!=null)return task(c,a,existing,false);
        return submitResolved(c,a,conversation,key,request,resolveV2(c,a,request,admissionHash));
    });}

    public Map<String,Object> diagnostics(Actor a,UUID id){return tx(c->{
        auth(c,a);Task task=task(c,a,id,false);Map<String,Object> result=new LinkedHashMap<>();
        boolean unknown;{ var rows5 = c.accounting().selectModelCall5(id, id); var row5 = rows5.isEmpty() ? null : rows5.getFirst(); unknown=row5.value1(); }
        String code=task.reasonCode();
        if(code==null&&"QUEUED".equals(task.status())){ var rows4 = c.dispatch().selectEventOutbox4(id); var row4 = rows4.isEmpty() ? null : rows4.getFirst(); if(row4 != null)code=row4.value1(); }
        String category=com.agentplatform.types.FailureRecorder.classification(code,unknown);
        { var rows3 = c.conversation().selectTask3(id); var row3 = rows3.isEmpty() ? null : rows3.getFirst(); result.put("traceId",row3.value1().toString()); }
        result.put("taskId",id.toString());result.put("code",code);result.put("classification",category);
        result.put("suggestedAction",com.agentplatform.types.FailureRecorder.action(category));result.put("automaticReplayAllowed",false);
        { var rows2 = c.conversation().selectMessage2(id); var row2 = rows2.isEmpty() ? null : rows2.getFirst(); result.put("hasPartialOutput",row2.value1()); }
        return result;
    });}
}
