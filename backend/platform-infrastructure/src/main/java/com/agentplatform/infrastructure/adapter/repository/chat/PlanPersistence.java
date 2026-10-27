package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.infrastructure.dao.chat.ChatMappers;
import com.agentplatform.infrastructure.dao.po.PlanRows.*;
import com.agentplatform.types.PlanInteractionData.*;
import com.agentplatform.types.Uuid7;
import com.agentplatform.types.harness.HarnessData;
import java.util.*;

/** Durable control intents and user input. All methods require the caller's Spring transaction. */
final class PlanPersistence extends ChatPersistenceSupport implements PlanLedger {
    PlanPersistence(ChatMappers mappers){super(mappers);}
    private static Revision revision(RevisionRow row){return row==null?null:new Revision(row.id(),row.revision(),row.title(),row.markdown(),row.source(),row.contentHash(),row.createdAt());}
    private Plan projection(Actor actor,UUID task){
        var p=mappers.plan().plan(actor.spaceId(),task);
        if(p==null)return new Plan(task,"NOT_CREATED",0,null,List.of());
        var revisions=mappers.plan().revisions(p.id()).stream().map(PlanPersistence::revision).toList();
        return new Plan(task,p.status(),p.version(),revisions.isEmpty()?null:revisions.getLast(),revisions);
    }
    public Plan plan(Actor a,UUID id){return tx(c->{auth(c,a);task(c,a,id,false);return projection(a,id);});}
    private Pending pending(InteractionRow row){
        PlanControls.Question q="QUESTION".equals(row.kind())?JSON.readValue(row.payloadJson(),PlanControls.Question.class):null;
        return new Pending(row.id(),row.taskId(),Kind.valueOf(row.kind()),row.optional(),row.version(),row.expiresAt(),
                revision(row.revisionId()==null?null:mappers.plan().revision(row.revisionId())),q==null?null:q.question(),
                q==null?List.of():q.options().stream().map(o->new Option(o.id(),o.label())).toList(),
                "TOOL_APPROVAL".equals(row.kind())?new ApprovalPersistence(mappers).projection(row):null);
    }
    public List<Pending> pending(Actor a,UUID conversation){return tx(true,c->{auth(c,a);conversation(c,a,conversation,false);return c.plan().pending(a.spaceId(),conversation).stream().map(this::pending).toList();});}
    private Task lock(Actor actor,UUID task){
        auth(mappers,actor);var hint=task(mappers,actor,task,false);
        new MyBatisQuotaLedger(mappers.quota()).lock(actor.userId());mappers.dispatch().updateExecutionSlot87();
        conversation(mappers,actor,hint.conversationId(),true);return task(mappers,actor,task,true);
    }
    private Revision write(Actor actor,UUID task,String title,String markdown,String source){
        new Edit(title,markdown);
        mappers.plan().insertPlan(Uuid7.next(),actor.spaceId(),task);
        var plan=mappers.plan().plan(actor.spaceId(),task);UUID id=Uuid7.next();
        mappers.plan().insertRevision(id,plan,title,markdown,hash(MyBatisExecutionLedger.canonicalJson(new Edit(title,markdown))),source);mappers.plan().advancePlan(plan.id(),"DRAFT",true);
        var result=revision(mappers.plan().revision(id));
        event(mappers,actor,task(mappers,actor,task,false).conversationId(),task,"plan.revised",plan.version()+1,
                Map.of("taskId",task.toString(),"revisionId",id.toString(),"revision",result.revision(),"version",plan.version()+1));
        return result;
    }
    private UUID open(Lease lease,String call,String kind,boolean optional,String payload,UUID revision){
        return open(new Actor(lease.userId(),lease.spaceId()),lease.taskId(),lease.conversationId(),lease.attemptId(),call,kind,optional,payload,revision);
    }
    private UUID open(Actor actor,UUID task,UUID conversation,UUID sourceAttempt,String call,String kind,boolean optional,String payload,UUID revision){
        return open(actor,task,conversation,sourceAttempt,call,kind,optional,payload,revision,null);
    }
    private UUID open(Actor actor,UUID task,UUID conversation,UUID sourceAttempt,String call,String kind,boolean optional,String payload,UUID revision,java.time.Instant expires){
        UUID id=Uuid7.next();mappers.plan().insertInteraction(id,actor.spaceId(),task,sourceAttempt,call,kind,optional,payload,revision,expires);
        var row=mappers.plan().interaction(actor.spaceId(),task,id);
        event(mappers,actor,conversation,task,"interaction.opened",row.version(),
                Map.of("interactionId",id.toString(),"taskId",task.toString(),"kind",kind,"optional",optional,"version",row.version(),"expiresAt",row.expiresAt().toString()));
        return id;
    }
    public Plan editPlan(Actor actor,UUID task,long expected,Edit edit){return tx(c->{
        var t=lock(actor,task);var current=projection(actor,task);
        if(current.version()!=expected)throw new ChatFailure(412,"PLAN_VERSION_CONFLICT");
        if(!"WAITING_INPUT".equals(t.status()))throw new ChatFailure(409,"PLAN_NOT_EDITABLE");
        var old=c.plan().taskPending(task).stream().filter(i->"PLAN_APPROVAL".equals(i.kind())&&!i.optional()).findFirst().orElseThrow(()->new ChatFailure(409,"PLAN_NOT_UNDER_REVIEW"));
        if(!old.expiresAt().isAfter(c.recovery().now()))throw new ChatFailure(409,"INTERACTION_EXPIRED");
        c.plan().closeInteraction(old.id(),"SUPERSEDED");
        var revised=write(actor,task,edit.title(),edit.markdown(),"USER");
        var control=c.plan().control(task,old.callId());
        UUID review=open(actor,task,t.conversationId(),old.sourceAttemptId(),old.callId(),"PLAN_APPROVAL",false,"{}",revised.id());
        c.plan().bindControl(control.id(),review);c.plan().advancePlan(c.plan().plan(actor.spaceId(),task).id(),"REVIEW",false);
        return projection(actor,task);
    });}
    public void stageControls(Lease lease,List<HarnessData.Call> calls){tx(c->{
        MyBatisExecutionLedger.guard(c,lease);
        if(calls.isEmpty())return null;
        if(calls.size()!=1||!PlanControls.NAMES.contains(calls.getFirst().name()))throw new ChatFailure(422,"CONTROL_BATCH_LIMIT");
        var call=calls.getFirst();String digest=hash(MyBatisExecutionLedger.canonicalJson(call));
        var existing=c.plan().control(lease.taskId(),call.id());
        if(existing!=null){
            if(!digest.equals(existing.callHash()))throw new ChatFailure(409,"CONTROL_CALL_CONFLICT");
            if(existing.interactionId()!=null){
                var old=c.plan().interaction(lease.spaceId(),lease.taskId(),existing.interactionId());
                if(!old.optional()&&"OPEN".equals(old.status())&&!old.sourceAttemptId().equals(lease.attemptId())){
                    if(c.recovery().hasStop(lease.taskId(),old.sourceAttemptId())!=1)throw new ChatFailure(409,"EXECUTION_STOP_NOT_CONFIRMED");
                    if(!old.expiresAt().isAfter(c.recovery().now()))throw new ChatFailure(409,"INTERACTION_EXPIRED");
                    c.plan().closeInteraction(old.id(),"SUPERSEDED");var actor=new Actor(lease.userId(),lease.spaceId());
                    event(c,actor,lease.conversationId(),lease.taskId(),"interaction.resolved",old.version()+1,Map.of("taskId",lease.taskId().toString(),"interactionId",old.id().toString(),"decision","SUPERSEDED","version",old.version()+1));
                    UUID replacement=open(actor,lease.taskId(),lease.conversationId(),lease.attemptId(),call.id(),old.kind(),false,old.payloadJson(),old.revisionId(),old.expiresAt());
                    c.plan().bindControl(existing.id(),replacement);
                }else if(!Set.of("OPEN","ANSWERED").contains(old.status()))throw new ChatFailure(409,"INTERACTION_CLOSED");
            }
            return null;
        }
        if(!c.executionPolicy().policy(lease.taskId()).planMode()&&"platform_plan_write".equals(call.name()))throw new ChatFailure(403,"APPROVED_PLAN_IMMUTABLE_FOR_EXECUTION");
        UUID id=Uuid7.next();c.plan().insertControl(id,lease,call.id(),call.name(),digest,json(call.arguments()));
        UUID interaction=null;
        if("platform_plan_review".equals(call.name())){
            var plan=projection(new Actor(lease.userId(),lease.spaceId()),lease.taskId());
            if(plan.current()==null)throw new ChatFailure(409,"PLAN_REQUIRED_BEFORE_REVIEW");
            interaction=open(lease,call.id(),"PLAN_APPROVAL",false,"{}",plan.current().id());
            c.plan().advancePlan(c.plan().plan(lease.spaceId(),lease.taskId()).id(),"REVIEW",false);
        }else if("platform_ask_user".equals(call.name())){
            var question=JSON.readValue(json(call.arguments()),PlanControls.Question.class);
            interaction=open(lease,call.id(),"QUESTION",question.optional(),json(question),null);
        }
        if(interaction!=null)c.plan().bindControl(id,interaction);
        return null;
    });}
    public HarnessData.Decision controlDecision(Lease lease,HarnessData.Call call){return tx(c->{
        MyBatisExecutionLedger.guard(c,lease);var control=requireControl(lease,call);
        if(control.interactionId()==null)return HarnessData.Decision.ALLOW;
        var interaction=c.plan().interaction(lease.spaceId(),lease.taskId(),control.interactionId());
        if(interaction.optional())return HarnessData.Decision.ALLOW;
        var answer=c.plan().answer(interaction.id());
        if(answer==null)return HarnessData.Decision.ASK;
        var resume=c.plan().resume(lease.attemptId());
        String answerHash=hash(MyBatisExecutionLedger.canonicalJson(List.of(lease.taskId(),interaction.id(),JSON.readValue(answer.contentJson(),Answer.class))));
        if(resume==null||!resume.answerId().equals(answer.id())||!resume.callId().equals(call.id())||!resume.answerHash().equals(answer.requestHash())
                ||!answerHash.equals(answer.requestHash())||!Objects.equals(resume.reviewRevisionId(),interaction.revisionId())||!resume.sourceAttemptId().equals(interaction.sourceAttemptId()))
            throw new ChatFailure(409,"CONTROL_ANSWER_BINDING_MISMATCH");
        return HarnessData.Decision.ALLOW;
    });}
    private ControlRow requireControl(Lease lease,HarnessData.Call call){
        var row=mappers.plan().control(lease.taskId(),call.id());
        if(row==null||!row.callHash().equals(hash(MyBatisExecutionLedger.canonicalJson(call))))throw new ChatFailure(409,"CONTROL_NOT_COMMITTED");
        return row;
    }
    public HarnessData.Observation executeControl(Lease lease,HarnessData.Call call){return tx(c->{
        MyBatisExecutionLedger.guard(c,lease);var row=requireControl(lease,call);
        if(controlDecision(lease,call)!=HarnessData.Decision.ALLOW)throw new ChatFailure(409,"CONTROL_ANSWER_REQUIRED");
        if("COMMITTED".equals(row.status()))return JSON.readValue(row.resultJson(),HarnessData.Observation.class);
        String text;
        if("platform_plan_write".equals(call.name())){
            var value=JSON.readValue(row.argumentsJson(),PlanControls.Write.class);
            var revised=write(new Actor(lease.userId(),lease.spaceId()),lease.taskId(),value.title(),value.markdown(),"MODEL");
            text=json(Map.of("revisionId",revised.id(),"revision",revised.revision(),"instruction","Plan saved. Call platform_plan_review in a separate round. No business tools are authorized."));
        }else{
            var interaction=c.plan().interaction(lease.spaceId(),lease.taskId(),row.interactionId());var answer=c.plan().answer(interaction.id());
            if(answer==null)text=json(Map.of("optional",true,"instruction","Optional question opened; continue without inventing an answer."));
            else{
                var result=new LinkedHashMap<String,Object>();result.put("decision",answer.decision());result.put("answer",object(answer.contentJson()));
                if("PLAN_APPROVAL".equals(interaction.kind()))result.put("EXECUTE_PLAN".equals(answer.decision())?"approvedPlan":"planUnderDiscussion",revision(c.plan().revision(interaction.revisionId())));
                result.put("instruction",switch(answer.decision()){
                    case "EXECUTE_PLAN" -> "Continue text processing using exactly approvedPlan. No file, shell or business tool permissions are granted.";
                    case "REVISE_PLAN", "REJECT_PLAN" -> "Do not execute the rejected or superseded plan. Discuss the supplied feedback, save a new revision and request a new review. No business tools are authorized.";
                    default -> "This is clarification data only, not an execution or permission grant. Do not invent answers. Continue the text-only planning discussion.";
                });
                text=json(result);
            }
        }
        var observation=new HarnessData.Observation(call.id(),call.name(),text,HarnessData.OperationStatus.SUCCEEDED);
        if(c.plan().finishControl(row.id(),json(observation))!=1)throw new ChatFailure(409,"CONTROL_RESULT_CONFLICT");
        return observation;
    });}
    public void waitingStopped(Lease lease,String receipt){tx(c->{
        owned(c,lease,true);var t=task(c,new Actor(lease.userId(),lease.spaceId()),lease.taskId(),false);
        if(t.cancelRequested()) {new ControlPersistence(c).stopped(lease,"CANCELLED","TASK_CANCEL_REQUESTED",null,receipt);return null;}
        try{auth(c,new Actor(lease.userId(),lease.spaceId()));authorizeCredential(c,lease);activeModel(c,lease);}
        catch(ChatFailure revoked){new ControlPersistence(c).stopped(lease,"FAILED","AUTHORIZATION_REVOKED",null,receipt);return null;}
        var checkpoint=c.recovery().latestMetadata(lease.taskId());
        var blocking=c.plan().taskPending(lease.taskId()).stream().filter(i->!i.optional()).findFirst().orElse(null);
        String expectedPhase=blocking!=null&&"TOOL_APPROVAL".equals(blocking.kind())?"WAITING_APPROVAL":"WAITING_INPUT";
        if(checkpoint==null||!checkpoint.attemptId().equals(lease.attemptId())||!expectedPhase.equals(checkpoint.phase()))
            throw new ChatFailure(409,"WAITING_CHECKPOINT_REQUIRED");
        if(c.recovery().unknown(lease.taskId())>0){new ControlPersistence(c).stopped(lease,"BLOCKED","EXTERNAL_RESULT_UNKNOWN",null,receipt);return null;}
        if(c.plan().taskPending(lease.taskId()).stream().noneMatch(i->!i.optional()))throw new ChatFailure(409,"WAITING_INTERACTION_REQUIRED");
        if(receipt==null||!receipt.matches("[a-f0-9]{64}"))throw new ChatFailure(409,"STOP_RECEIPT_INVALID");
        c.control().recordStop(lease,receipt);if(c.control().stopCount(lease)!=1)throw new ChatFailure(409,"STOP_RECEIPT_MISMATCH");
        if(c.plan().waitingTask(lease.taskId())!=1)throw new ChatFailure(409,"WAITING_STATE_CONFLICT");
        c.executionPolicy().stopActivity(lease.taskId());c.dispatch().updateAttempt137("WAITING_INPUT",null,lease.attemptId());
        c.dispatch().updateExecutionDispatch138(lease.taskId());c.dispatch().deleteConversationLease139(lease.attemptId());c.dispatch().updateExecutionSlot140();
        c.plan().finishResume(lease.attemptId());c.recovery().finishRequest(lease.attemptId(),true);
        var actor=new Actor(lease.userId(),lease.spaceId());event(c,actor,lease.conversationId(),lease.taskId(),"task.state_changed",task(c,actor,lease.taskId(),false).version(),Map.of("taskId",lease.taskId().toString(),"status","WAITING_INPUT"));return null;
    });}
    public boolean planMode(Lease lease){return tx(c->{owned(c,lease,false);return c.executionPolicy().policy(lease.taskId()).planMode();});}
    public void consumeOptional(Lease lease){tx(c->{MyBatisExecutionLedger.guard(c,lease);c.plan().consumeOptional(lease);return null;});}
    public List<HarnessData.Approval> controlApprovals(Lease lease){return tx(c->{
        owned(c,lease,false);var resume=c.plan().resume(lease.attemptId());if(resume==null)return List.of();
        var control=c.plan().control(lease.taskId(),resume.callId());
        if(control==null)return List.of();
        var call=new HarnessData.Call(control.callId(),control.toolName(),object(control.argumentsJson()),false);
        if(controlDecision(lease,call)!=HarnessData.Decision.ALLOW)throw new ChatFailure(409,"CONTROL_ANSWER_REQUIRED");
        return List.of(new HarnessData.Approval(call,true));
    });}
    public Accepted answer(Actor actor,UUID task,UUID interaction,UUID command,Answer answer){return tx(c->{
        var t=lock(actor,task);String digest=hash(MyBatisExecutionLedger.canonicalJson(List.of(task,interaction,answer)));
        var existing=c.plan().command(actor.userId(),command);
        if(existing!=null){if(!digest.equals(existing.requestHash()))throw new ChatFailure(409,"IDEMPOTENCY_CONFLICT");return new Accepted(task,interaction,t.status(),t.version());}
        var row=c.plan().interaction(actor.spaceId(),task,interaction);if(row==null)throw new ChatFailure(404,"RESOURCE_NOT_FOUND");
        String approvalPath="/tasks/"+task+"/interactions/"+interaction+"/answer";
        if("TOOL_APPROVAL".equals(row.kind())){
            var replay=idempotency(c,actor,command,approvalPath,digest);
            if(replay!=null)return new Accepted(task,interaction,t.status(),t.version());
            var prior=c.plan().answer(interaction);
            if(prior!=null){
                if(!prior.decision().equals(answer.decision().name()))throw new ChatFailure(409,"INTERACTION_ALREADY_RESOLVED");
                if(!MyBatisExecutionLedger.canonicalJson(JSON.readValue(prior.contentJson(),Answer.class)).equals(MyBatisExecutionLedger.canonicalJson(answer)))throw new ChatFailure(409,"IDEMPOTENCY_CONFLICT");
                remember(c,actor,command,approvalPath,digest,interaction);return new Accepted(task,interaction,t.status(),t.version());
            }
        }
        if(row.version()!=answer.expectedVersion()||!"OPEN".equals(row.status()))throw new ChatFailure(412,"INTERACTION_VERSION_CONFLICT");
        if(!row.expiresAt().isAfter(c.recovery().now()))throw new ChatFailure(409,"TOOL_APPROVAL".equals(row.kind())?"APPROVAL_EXPIRED":"INTERACTION_EXPIRED");
        if(!Set.of("RUNNING","WAITING_INPUT").contains(t.status())||t.cancelRequested())throw new ChatFailure(409,"TASK_NOT_ACCEPTING_INPUT");
        validateAnswer(row,answer);
        var cp=c.recovery().latestMetadata(task);
        String expectedPhase="TOOL_APPROVAL".equals(row.kind())?"WAITING_APPROVAL":"WAITING_INPUT";
        if(!row.optional()&&(!"WAITING_INPUT".equals(t.status())||cp==null||!expectedPhase.equals(cp.phase())||!row.sourceAttemptId().equals(cp.attemptId())||c.recovery().hasStop(task,row.sourceAttemptId())!=1||c.recovery().live(task)>0))
            throw new ChatFailure(409,"EXECUTION_STOP_NOT_CONFIRMED");
        UUID id=Uuid7.next();c.plan().insertAnswer(id,row,actor.userId(),command,digest,answer.decision().name(),json(answer),com.agentplatform.types.RequestTrace.current());c.plan().closeInteraction(interaction,"ANSWERED");
        if(answer.decision()==Decision.DELIVER_PLAN){
            var plan=projection(actor,task);var message=c.conversation().selectMessage30(actor.spaceId(),task).getFirst();
            c.conversation().updateMessage114(json(textBlocks(message.value1(),plan.current().markdown())),message.value3()+1,message.value1());
            c.conversation().updateMessage136("COMPLETED",message.value3()+1,message.value1());c.conversation().updateTask135("SUCCEEDED",null,task);
            c.plan().advancePlan(c.plan().plan(actor.spaceId(),task).id(),"DELIVERED",false);c.plan().closeTaskInteractions(task,"CANCELLED");assistantTerminalEvent(c,actor,t.conversationId(),task);
        }else if(!row.optional()){
            if(answer.decision()==Decision.EXECUTE_PLAN){c.plan().advancePlan(c.plan().plan(actor.spaceId(),task).id(),"APPROVED",false);c.plan().updatePlanOption(task,false);}
            else if(answer.decision()==Decision.REJECT_PLAN)c.plan().advancePlan(c.plan().plan(actor.spaceId(),task).id(),"REJECTED",false);
            long revision=c.recovery().task(task).revision()+1;
            var restore=new RecoveryLedger.RestoreCommand(command,task,cp.id(),cp.attemptId(),t.version(),revision-1,"interaction-answer");
            c.recovery().insertRequest(restore,actor.spaceId(),revision,digest);c.recovery().queueTask(task,revision,0,0);c.recovery().queueDispatch(task);
            long generation=c.recovery().generation(task);c.plan().insertResume(Uuid7.next(),row,cp.id(),id,digest,row.revisionId(),generation);outbox(c,actor,task,generation);
        }
        var updated=task(c,actor,task,false);
        event(c,actor,t.conversationId(),task,"interaction.resolved",row.version()+1,Map.of("interactionId",interaction.toString(),"taskId",task.toString(),"decision",answer.decision().name(),"version",row.version()+1));
        event(c,actor,t.conversationId(),task,"task.state_changed",updated.version(),Map.of("taskId",task.toString(),"status",updated.status()));
        if("TOOL_APPROVAL".equals(row.kind()))remember(c,actor,command,approvalPath,digest,interaction);
        return new Accepted(task,interaction,updated.status(),updated.version());
    });}
    private void validateAnswer(InteractionRow row,Answer answer){
        if("TOOL_APPROVAL".equals(row.kind())){
            new ApprovalPersistence(mappers).validateAnswer(row,answer);return;
        }else if("PLAN_APPROVAL".equals(row.kind())){
            if(!Set.of(Decision.EXECUTE_PLAN,Decision.REVISE_PLAN,Decision.REJECT_PLAN,Decision.DELIVER_PLAN).contains(answer.decision())||!answer.optionIds().isEmpty())throw new ChatFailure(422,"INTERACTION_ANSWER_INVALID");
            if(answer.decision()==Decision.REVISE_PLAN&&answer.text().isBlank())throw new ChatFailure(422,"REVISION_REQUEST_REQUIRED");
        }else{
            if(answer.decision()==Decision.SKIP){if(!row.optional()||!answer.text().isEmpty()||!answer.optionIds().isEmpty())throw new ChatFailure(422,"QUESTION_SKIP_NOT_ALLOWED");}
            else if(answer.decision()!=Decision.ANSWER||answer.text().isBlank()&&answer.optionIds().isEmpty())throw new ChatFailure(422,"QUESTION_ANSWER_REQUIRED");
            var q=JSON.readValue(row.payloadJson(),PlanControls.Question.class);var ids=q.options().stream().map(PlanControls.Option::id).toList();
            if(!ids.containsAll(answer.optionIds()))throw new ChatFailure(422,"QUESTION_OPTION_INVALID");
        }
        // Conservative byte-count context admission; no persisted answer is silently truncated.
        var history=mappers.conversation().selectMessage37(row.spaceId(),mappers.recovery().task(row.taskId()).conversationId(),row.taskId());
        long bytes=answer.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        for(var message:history)bytes+=message.value2().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(row.revisionId()!=null)bytes+=mappers.plan().revision(row.revisionId()).markdown().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(bytes>6144)throw new ChatFailure(422,"CONTEXT_REQUIRED_TOO_LARGE");
    }
    void closeInteractions(Actor actor,UUID task,String state){
        var t=task(mappers,actor,task,false);
        for(var pending:mappers.plan().taskPending(task))if(mappers.plan().closeInteraction(pending.id(),state)==1)
            event(mappers,actor,t.conversationId(),task,"interaction.resolved",pending.version()+1,Map.of("taskId",task.toString(),"interactionId",pending.id().toString(),"decision",state,"version",pending.version()+1));
    }
    void expireOne(){
        UUID id=mappers.plan().expiringTask();if(id==null)return;
        var hint=mappers.recovery().task(id);var actor=new Actor(hint.userId(),hint.spaceId());
        new MyBatisQuotaLedger(mappers.quota()).lock(actor.userId());mappers.dispatch().updateExecutionSlot87();
        conversation(mappers,actor,hint.conversationId(),true);var task=task(mappers,actor,id,true);
        boolean required=false;
        for(var row:mappers.plan().staleInteractions(id)){
            if(mappers.plan().closeInteraction(row.id(),"EXPIRED")!=1)continue;
            required|=!row.optional();
            event(mappers,actor,task.conversationId(),id,"interaction.resolved",row.version()+1,Map.of("taskId",id.toString(),"interactionId",row.id().toString(),"decision","EXPIRED","version",row.version()+1));
        }
        if(required&&"WAITING_INPUT".equals(task.status())){
            if(mappers.control().latestStopCount(id)!=1||mappers.recovery().live(id)>0)throw new ChatFailure(409,"EXECUTION_STOP_NOT_CONFIRMED");
            closeInteractions(actor,id,"CANCELLED");mappers.conversation().updateTask135("FAILED","INPUT_EXPIRED",id);mappers.conversation().updateMessage90(id);
            assistantTerminalEvent(mappers,actor,task.conversationId(),id);
            event(mappers,actor,task.conversationId(),id,"task.state_changed",task(mappers,actor,id,false).version(),Map.of("taskId",id.toString(),"status","FAILED","reasonCode","INPUT_EXPIRED"));
        }
    }
}
