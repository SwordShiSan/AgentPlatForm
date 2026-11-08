package com.agentplatform.app.knowledge;

import com.agentplatform.api.knowledge.PersonalMemoryApi;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.domain.knowledge.KnowledgeData.*;
import com.agentplatform.domain.knowledge.MemoryRepository;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;

public final class PersonalMemoryApplicationService implements PersonalMemoryApi {
    private final MemoryRepository repository;
    public PersonalMemoryApplicationService(MemoryRepository repository){this.repository=repository;}
    @Override public View candidate(Caller caller,String requestId,Candidate body){
        if(body==null)throw new FileFailure(400,"INVALID_MEMORY_CANDIDATE");
        String content=normalize(body.content(),8192,"MEMORY_CONTENT_INVALID");
        String topic=normalize(body.topicKey(),256,"MEMORY_TOPIC_INVALID");
        String scope=required(body.scopeKind(),"MEMORY_SCOPE_INVALID");String category=required(body.category(),"MEMORY_CATEGORY_INVALID");
        if(!List.of("PERSONAL","WORKSPACE").contains(scope)||!List.of("PREFERENCE","FACT","PROCEDURE").contains(category))throw new FileFailure(400,"MEMORY_CLASSIFICATION_INVALID");
        var actor=actor(caller);var workspace=body.workspaceId()==null?null:KnowledgeApplicationService.id(body.workspaceId());
        if(("PERSONAL".equals(scope))!=(workspace==null))throw new FileFailure(400,"MEMORY_SCOPE_INVALID");
        String requestHash=KnowledgeApplicationService.hash("CANDIDATE\n"+scope+"\n"+workspace+"\n"+category+"\n"+topic+"\n"+content);
        return view(repository.candidate(actor,scope,workspace,category,topic,content,KnowledgeApplicationService.id(requestId),requestHash));
    }
    @Override public List<View> list(Caller caller,String status,String workspaceId){return repository.list(actor(caller),status,workspaceId==null?null:KnowledgeApplicationService.id(workspaceId)).stream().map(PersonalMemoryApplicationService::view).toList();}
    @Override public View change(Caller caller,String id,String requestId,Change body){
        if(body==null||body.expectedRevision()<1)throw new FileFailure(400,"INVALID_MEMORY_REVISION");
        String action=required(body.action(),"MEMORY_ACTION_REQUIRED");if(!List.of("CONFIRM","REJECT","EDIT","SUPERSEDE","DELETE").contains(action))throw new FileFailure(400,"MEMORY_ACTION_INVALID");
        String category=body.category()==null?null:required(body.category(),"MEMORY_CATEGORY_INVALID");
        if(category!=null&&!List.of("PREFERENCE","FACT","PROCEDURE").contains(category))throw new FileFailure(400,"MEMORY_CATEGORY_INVALID");
        String topic=body.topicKey()==null?null:normalize(body.topicKey(),256,"MEMORY_TOPIC_INVALID");
        String content=body.content()==null?null:normalize(body.content(),8192,"MEMORY_CONTENT_INVALID");
        String input=action+"\n"+id+"\n"+body.expectedRevision()+"\n"+category+"\n"+topic+"\n"+content;
        return view(repository.change(actor(caller),KnowledgeApplicationService.id(id),body.expectedRevision(),action,category,topic,content,
                KnowledgeApplicationService.id(requestId),KnowledgeApplicationService.hash(input)));
    }
    private static String normalize(String value,int bytes,String code){if(value==null)throw new FileFailure(400,code);String text=Normalizer.normalize(value.strip(),Normalizer.Form.NFC);if(text.isBlank()||text.getBytes(StandardCharsets.UTF_8).length>bytes)throw new FileFailure(400,code);return text;}
    private static String required(String value,String code){if(value==null||value.isBlank())throw new FileFailure(400,code);return value;}
    private static Actor actor(Caller c){if(c==null)throw new FileFailure(401,"AUTHENTICATION_REQUIRED");return new Actor(KnowledgeApplicationService.id(c.userId()),KnowledgeApplicationService.id(c.spaceId()));}
    private static View view(Memory m){return new View(m.id().toString(),m.scopeKind(),m.workspaceId()==null?null:m.workspaceId().toString(),m.category(),m.topicKey(),m.content(),m.status(),m.revision(),m.expiresAt(),m.confirmedAt());}
}
