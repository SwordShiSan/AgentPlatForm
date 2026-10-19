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
    ConversationPersistence(ChatMappers mappers) { super(mappers); }

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
        return new Snapshot(id,cursor,List.copyOf(messages),List.copyOf(tasks));
    });}
    public Task submit(Actor a,UUID convo,UUID key,UUID model,String text){return tx(c->{String source=null;{ var rows60 = c.configuration().selectModelBinding60(a.spaceId(), a.userId(), model); var row60 = rows60.isEmpty() ? null : rows60.getFirst(); if(row60 != null)source=row60.value1(); }return submit(c,a,convo,key,model,source,text);});}
    public Task submitConfigured(Actor a,UUID convo,UUID key,UUID model,String source,String text){return tx(c->submit(c,a,convo,key,model,source,text));}
    public Task task(Actor a,UUID id){return tx(c->{auth(c,a);return task(c,a,id,false);});}
    public Task cancel(Actor a,UUID id,UUID key){return tx(c->{
        auth(c,a);Task t=task(c,a,id,false);c.dispatch().updateExecutionSlot87();conversation(c,a,t.conversationId(),true);t=task(c,a,id,true);
        String path="/tasks/"+id+"/cancel";UUID existing=idempotency(c,a,key,path,hash("cancel"));if(existing!=null)return t;
        if("QUEUED".equals(t.status())){c.conversation().updateTask88(id);c.dispatch().updateExecutionDispatch89(id);releaseRemaining(c,a,id);c.conversation().updateMessage90(id);assistantTerminalEvent(c,a,t.conversationId(),id);}
        else if("RUNNING".equals(t.status()))c.conversation().updateTask91(id);
        remember(c,a,key,path,hash("cancel"),id);Task now=task(c,a,id,false);event(c,a,t.conversationId(),id,"task.state_changed",now.version(),Map.of("taskId",id.toString(),"status",now.status(),"cancelRequested",now.cancelRequested()));return now;
    });}
    public List<Event> events(Actor a,UUID id,long after){return tx(c->{
        auth(c,a);conversation(c,a,id,false);
        long current;{ var rows53 = c.conversation().selectConversation53(id); var row53 = rows53.isEmpty() ? null : rows53.getFirst(); current=row53.value1();if(after<0||after>current)throw new ChatFailure(409,"INVALID_CURSOR"); }
        { var rows52 = c.conversation().selectPlatformEvent52(id); var row52 = rows52.isEmpty() ? null : rows52.getFirst();
            Long first=row52==null?null:row52.value1();if(after<current&&(first==null||after<first-1))throw new ChatFailure(410,"CURSOR_EXPIRED");
         }
        List<Event> result=new ArrayList<>();{ var rows51 = c.conversation().selectPlatformEvent51(a.spaceId(), id, after);
            for (var row51 : rows51)result.add(new Event(row51.id(),a.spaceId(),id,row51.taskId(),row51.seq(),row51.type(),row51.entityVersion(),row51.createdAt(),object(row51.payloadJson())));
         }return List.copyOf(result);
    });}
    public List<Usage> usage(Actor a,UUID id){return tx(c->{auth(c,a);task(c,a,id,false);List<Usage> result=new ArrayList<>();{ var rows50 = c.accounting().selectModelCall50(a.spaceId(), id); for (var row50 : rows50)result.add(new Usage(row50.id(),row50.status(),row50.inputTokens(),row50.outputTokens(),row50.costMicros(),row50.estimated())); }return List.copyOf(result);});}
    public First first(Actor a,UUID key,String title,UUID model,String source,String text){return tx(c->{
        auth(c,a);String name=title==null?"新对话":title;if(name.isBlank()||name.codePointCount(0,name.length())>120)throw new ChatFailure(400,"INVALID_TITLE");
        String fingerprint=hash(json(Arrays.asList(name,model,source,text)));UUID existing=idempotency(c,a,key,"/conversations",fingerprint);
        if(existing!=null){Conversation convo;{ var rows18 = c.conversation().selectConversation18(a.spaceId(), existing); var row18 = rows18.isEmpty() ? null : rows18.getFirst(); if(row18 == null)throw new ChatFailure(404,"RESOURCE_NOT_FOUND");convo=readConversation(row18); }{ var rows17 = c.conversation().selectTask17(existing); var row17 = rows17.isEmpty() ? null : rows17.getFirst(); if(row17 == null)throw new ChatFailure(409,"IDEMPOTENCY_CONFLICT");return new First(convo,readTask(row17)); }}
        UUID id=Uuid7.next();c.conversation().insertConversation164(id, a.spaceId(), a.userId(), name);
        Task task=submit(c,a,id,key,model,source,text);remember(c,a,key,"/conversations",fingerprint,id);
        { var rows16 = c.conversation().selectConversation16(id); var row16 = rows16.isEmpty() ? null : rows16.getFirst(); return new First(readConversation(row16),task); }
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
