package com.agentplatform.infrastructure.adapter.repository.knowledge;

import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.domain.knowledge.KnowledgeData.Actor;
import com.agentplatform.domain.knowledge.KnowledgeData.Memory;
import com.agentplatform.domain.knowledge.MemoryRepository;
import com.agentplatform.infrastructure.dao.knowledge.MemoryMapper;
import com.agentplatform.types.Uuid7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

public final class MyBatisMemoryRepository implements MemoryRepository {
    private final MemoryMapper mapper;
    private final TransactionTemplate tx;
    public MyBatisMemoryRepository(MemoryMapper mapper, TransactionTemplate tx) { this.mapper=mapper; this.tx=tx; }

    @Override public Memory candidate(Actor actor,String scope,UUID workspace,String category,String topic,String content,UUID request,String requestHash) {
        return tx.execute(status -> {
            requireActor(actor);
            if ("WORKSPACE".equals(scope) && !mapper.workspaceOwned(actor,workspace)) throw new FileFailure(404,"MEMORY_SCOPE_NOT_FOUND");
            var prior=mapper.request(actor,request);
            if(prior!=null) return replay(actor,prior,"CANDIDATE",requestHash);
            UUID id=Uuid7.next(); String contentHash=hash(content);
            try {
                mapper.insert(new MemoryMapper.InsertInput(actor,id,scope,workspace,category,topic,content,contentHash));
                Memory created=mapper.memory(actor,id); if(created==null) throw new FileFailure(503,"MEMORY_CREATE_UNCONFIRMED");
                mapper.audit(Uuid7.next(),actor,id,"CANDIDATE",created.revision(),contentHash);
                mapper.insertRequest(actor,request,"CANDIDATE",requestHash,id,created.revision());
                return created;
            } catch(DuplicateKeyException conflict) { throw new FileFailure(409,"MEMORY_TOPIC_CONFLICT"); }
        });
    }

    @Override public List<Memory> list(Actor actor,String state,UUID workspace) {
        return tx.execute(s -> { requireActor(actor); if(workspace!=null&&!mapper.workspaceOwned(actor,workspace)) throw new FileFailure(404,"MEMORY_SCOPE_NOT_FOUND");
            mapper.expire(actor); return mapper.list(actor,state,workspace); });
    }

    @Override public Memory change(Actor actor,UUID id,long expected,String action,String category,String topic,String content,UUID request,String requestHash) {
        return tx.execute(status -> {
            requireActor(actor);mapper.expire(actor); var prior=mapper.request(actor,request); if(prior!=null) return replay(actor,prior,action,requestHash);
            Memory current=mapper.lockMemory(actor,id); if(current==null) throw new FileFailure(404,"MEMORY_NOT_FOUND");
            if(current.revision()!=expected) throw new FileFailure(412,"MEMORY_REVISION_CONFLICT");
            try {
                if("CONFIRM".equals(action)) {
                    if(!"PENDING".equals(current.status())) throw new FileFailure(409,"MEMORY_NOT_PENDING");
                    var target=new MemoryMapper.Target(current.scopeKind(),current.workspaceId(),current.topicKey(),current.id());
                    mapper.supersedeTopic(actor,target);
                    if(mapper.activate(actor,id,expected)!=1) throw new FileFailure(412,"MEMORY_REVISION_CONFLICT");
                } else if("EDIT".equals(action)) {
                    String nextContent=content==null?current.content():content;
                    if(mapper.edit(actor,id,expected,category,topic,nextContent,hash(nextContent))!=1) throw new FileFailure(412,"MEMORY_REVISION_CONFLICT");
                } else {
                    String next=switch(action){case "REJECT"->"REJECTED";case "SUPERSEDE"->"SUPERSEDED";case "DELETE"->"DELETED";default->throw new FileFailure(400,"MEMORY_ACTION_INVALID");};
                    if(mapper.transition(actor,id,expected,current.status(),next)!=1) throw new FileFailure(412,"MEMORY_REVISION_CONFLICT");
                }
            } catch (DuplicateKeyException conflict) {
                throw new FileFailure(409,"MEMORY_TOPIC_CONFLICT");
            }
            Memory changed=mapper.memory(actor,id); if(changed==null) throw new FileFailure(503,"MEMORY_CHANGE_UNCONFIRMED");
            String contentHash=hash(changed.content()); mapper.audit(Uuid7.next(),actor,id,action,changed.revision(),contentHash);
            mapper.insertRequest(actor,request,action,requestHash,id,changed.revision()); return changed;
        });
    }

    @Override public List<Memory> recall(Actor actor,UUID workspace,int limit) {
        return tx.execute(s -> { requireActor(actor); if(workspace!=null&&!mapper.workspaceOwned(actor,workspace)) throw new FileFailure(404,"MEMORY_SCOPE_NOT_FOUND");
            mapper.expire(actor); return mapper.recall(actor,workspace,Math.min(100,Math.max(1,limit))); });
    }

    private Memory replay(Actor actor,MemoryMapper.MemoryRequest prior,String action,String requestHash) {
        if(!prior.action().equals(action)||!prior.requestHash().equals(requestHash)) throw new FileFailure(409,"IDEMPOTENCY_KEY_REUSED");
        Memory value=mapper.memory(actor,prior.memoryId()); if(value==null) throw new FileFailure(404,"MEMORY_NOT_FOUND"); return value;
    }
    private void requireActor(Actor actor) { if(actor==null||!mapper.authorized(actor)) throw new FileFailure(403,"MEMORY_ACCESS_DENIED"); }
    private static String hash(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
}
