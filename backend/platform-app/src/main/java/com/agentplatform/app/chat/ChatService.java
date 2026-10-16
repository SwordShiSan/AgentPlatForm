package com.agentplatform.app.chat;

import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.infrastructure.chat.ChatCursor;
import java.util.List;
import java.util.UUID;

public final class ChatService implements ChatApi {
    private final ChatRepository repository;private final ChatCursor cursor;
    public ChatService(ChatRepository repository,ChatCursor cursor){this.repository=repository;this.cursor=cursor;}
    private static UUID id(String value){try{return UUID.fromString(value);}catch(Exception invalid){throw new ChatFailure(400,"INVALID_UUID");}}
    private static Actor actor(Caller c){return new Actor(id(c.userId()),id(c.spaceId()));}
    private static ConversationView view(Conversation c){return new ConversationView(c.id().toString(),c.title(),c.version(),c.updatedAt().toString());}
    private static TaskView view(Task t){return new TaskView(t.id().toString(),t.conversationId().toString(),t.status(),t.cancelRequested(),t.reasonCode(),t.version());}
    public Models models(Caller c){return new Models(repository.models(actor(c)).stream().map(m->new ModelView(m.id().toString(),m.name(),List.of("TEXT"),true)).toList());}
    public ConversationView create(Caller c,String key,String title){return view(repository.create(actor(c),id(key),title));}
    public Conversations conversations(Caller c,String token,int limit){if(limit<1||limit>100)throw new ChatFailure(400,"INVALID_LIMIT");Actor a=actor(c);var values=repository.conversations(a,cursor.decode(a,token),limit);return new Conversations(values.stream().map(ChatService::view).toList(),values.size()==limit?cursor.encode(a,values.getLast().id()):null);}
    public ConversationView conversation(Caller c,String value){return view(repository.conversation(actor(c),id(value)));}
    public Accepted submit(Caller c,String conversation,String key,String model,String text){Actor a=actor(c);var task=repository.submit(a,id(conversation),id(key),id(model),text);return new Accepted(task.id().toString(),task.conversationId().toString(),task.status(),repository.snapshot(a,task.conversationId()).cursor());}
    public TaskView task(Caller c,String value){return view(repository.task(actor(c),id(value)));}
    public TaskView cancel(Caller c,String value,String key){return view(repository.cancel(actor(c),id(value),id(key)));}
    public Snapshot snapshot(Caller c,String value){var snapshot=repository.snapshot(actor(c),id(value));return new Snapshot(1,snapshot.cursor(),snapshot.messages().stream().map(m->new MessageView(m.id().toString(),m.taskId().toString(),m.role(),m.blocks(),m.status(),m.version())).toList(),snapshot.tasks().stream().map(ChatService::view).toList(),List.of(),List.of());}
    public List<EventView> events(Caller c,String value,long after){return repository.events(actor(c),id(value),after).stream().map(e->new EventView(1,e.id().toString(),e.spaceId().toString(),e.conversationId().toString(),e.taskId().toString(),e.seq(),e.type(),e.occurredAt().toString(),e.version(),e.payload())).toList();}
    public List<UsageView> usage(Caller c,String value){return repository.usage(actor(c),id(value)).stream().map(u->new UsageView(u.callId().toString(),u.status(),u.inputTokens(),u.outputTokens(),u.costMicros(),u.estimated())).toList();}
}
