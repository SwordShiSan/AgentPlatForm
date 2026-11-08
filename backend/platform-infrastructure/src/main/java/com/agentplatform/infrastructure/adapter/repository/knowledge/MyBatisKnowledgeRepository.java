package com.agentplatform.infrastructure.adapter.repository.knowledge;

import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.domain.knowledge.KnowledgeData.*;
import com.agentplatform.domain.knowledge.KnowledgeRepository;
import com.agentplatform.infrastructure.dao.knowledge.KnowledgeMapper;
import com.agentplatform.infrastructure.knowledge.KnowledgeModelIdentity;
import com.agentplatform.types.Uuid7;
import java.text.Normalizer;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

public final class MyBatisKnowledgeRepository implements KnowledgeRepository {
    private final KnowledgeMapper mapper; private final TransactionTemplate tx;
    public MyBatisKnowledgeRepository(KnowledgeMapper mapper,TransactionTemplate tx){this.mapper=mapper;this.tx=tx;}
    @Override public Base createBase(Actor actor,String name,UUID requestId,String requestHash){return tx.execute(s->{
        requireActor(actor);var prior=mapper.request(actor,requestId);if(prior!=null)return replayBase(actor,prior,"BASE_CREATE",requestHash);
        UUID id=Uuid7.next();try{mapper.insertBase(actor,id,name,Normalizer.normalize(name.toLowerCase(java.util.Locale.ROOT),Normalizer.Form.NFC));
            mapper.insertRequest(actor,requestId,"BASE_CREATE",requestHash,id);return required(mapper.base(actor,id),"KNOWLEDGE_BASE_CREATE_UNCONFIRMED");
        }catch(DuplicateKeyException conflict){throw new FileFailure(409,"KNOWLEDGE_BASE_NAME_CONFLICT");}
    });}
    @Override public List<Base> bases(Actor actor){require(actor);return mapper.bases(actor);}
    @Override public Base base(Actor actor,UUID id){require(actor);return mapper.base(actor,id);}
    @Override public Document addDocument(Actor actor,UUID base,UUID extraction,UUID requestId,String requestHash){return tx.execute(s->{
        requireActor(actor);var prior=mapper.request(actor,requestId);if(prior!=null)return replayDocument(actor,prior,"DOCUMENT_ADD",requestHash);
        if(mapper.base(actor,base)==null)throw new FileFailure(404,"KNOWLEDGE_BASE_NOT_FOUND");
        ExtractedSource source=mapper.extraction(actor,base,extraction);if(source==null)throw new FileFailure(404,"KNOWLEDGE_SOURCE_NOT_AVAILABLE");
        UUID doc=Uuid7.next(),version=Uuid7.next(),build=Uuid7.next(),outbox=Uuid7.next();
        try{mapper.insertDocument(actor,doc,base,source);queue(actor.spaceId(),doc,version,build,outbox,source);mapper.insertRequest(actor,requestId,"DOCUMENT_ADD",requestHash,doc);
            return required(mapper.document(actor,doc),"KNOWLEDGE_DOCUMENT_CREATE_UNCONFIRMED");
        }catch(DuplicateKeyException conflict){throw new FileFailure(409,"KNOWLEDGE_DOCUMENT_ALREADY_INDEXED");}
    });}
    @Override public Document document(Actor actor,UUID id){require(actor);return mapper.document(actor,id);}
    @Override public List<Document> documents(Actor actor,UUID base){require(actor);if(mapper.base(actor,base)==null)throw new FileFailure(404,"KNOWLEDGE_BASE_NOT_FOUND");return mapper.documents(actor,base);}
    @Override public Document rebuild(Actor actor,UUID id,UUID requestId,String requestHash){return tx.execute(s->{
        requireActor(actor);var prior=mapper.request(actor,requestId);if(prior!=null)return replayDocument(actor,prior,"DOCUMENT_REBUILD",requestHash);
        Document doc=mapper.document(actor,id);if(doc==null)throw new FileFailure(404,"KNOWLEDGE_DOCUMENT_NOT_FOUND");
        if(mapper.setRebuildState(actor,id)!=1)throw new FileFailure(409,"KNOWLEDGE_REBUILD_NOT_ALLOWED");
        ExtractedSource source=mapper.extraction(actor,doc.baseId(),doc.extractionId());if(source==null)throw new FileFailure(409,"KNOWLEDGE_SOURCE_REVOKED");
        UUID version=Uuid7.next(),build=Uuid7.next(),outbox=Uuid7.next();queue(actor.spaceId(),id,version,build,outbox,source);
        mapper.insertRequest(actor,requestId,"DOCUMENT_REBUILD",requestHash,id);return required(mapper.document(actor,id),"KNOWLEDGE_REBUILD_UNCONFIRMED");
    });}
    @Override public void deleteDocument(Actor actor,UUID id,UUID requestId,String requestHash){tx.executeWithoutResult(s->{
        requireActor(actor);var prior=mapper.request(actor,requestId);if(prior!=null){requireReplay(prior,"DOCUMENT_DELETE",requestHash);return;}
        if(mapper.deleteDocument(actor,id)!=1)throw new FileFailure(404,"KNOWLEDGE_DOCUMENT_NOT_FOUND");mapper.insertRequest(actor,requestId,"DOCUMENT_DELETE",requestHash,id);
    });}
    private void queue(UUID space,UUID document,UUID version,UUID build,UUID outbox,ExtractedSource source){
        String model=KnowledgeModelIdentity.MODEL_SHA256,tokenizer=KnowledgeModelIdentity.TOKENIZER_SHA256;
        if(mapper.insertIndex(space,document,version,model,tokenizer)!=1||mapper.insertBuild(build,space,document,version)!=1||mapper.insertOutbox(outbox,space,build)!=1)
            throw new FileFailure(503,"KNOWLEDGE_INDEX_QUEUE_UNCONFIRMED");
    }
    @Override public void guard(BuildCommand c){if(!mapper.live(c))throw new FileFailure(409,"KNOWLEDGE_BUILD_FENCE_LOST");}
    @Override public void renew(BuildCommand c){tx.executeWithoutResult(s->{guard(c);if(mapper.renew(c)!=1)throw new FileFailure(409,"KNOWLEDGE_BUILD_FENCE_LOST");});}
    @Override public List<Publication> publications(UUID owner){return tx.execute(s->mapper.publications(owner));}
    @Override public void published(Publication p,boolean confirmed){tx.executeWithoutResult(s->mapper.markPublished(p,confirmed));}
    @Override public BuildCommand claim(Publication item,UUID owner){return tx.execute(s->{
        if(!mapper.knownPublication(item))throw new FileFailure(409,"KNOWLEDGE_DELIVERY_UNKNOWN");
        var state=mapper.buildState(item);
        if(state==null)throw new FileFailure(409,"KNOWLEDGE_BUILD_NOT_FOUND");
        if(List.of("COMMITTED","FAILED","BLOCKED").contains(state.state()))return null;
        boolean inbox=mapper.knownInbox(item);
        if("RUNNING".equals(state.state())&&state.leaseLive())return null;
        if(!"QUEUED".equals(state.state())&&!(inbox&&"RUNNING".equals(state.state())))throw new FileFailure(409,"KNOWLEDGE_BUILD_NOT_QUEUED");
        if(mapper.heavy()==null)throw new FileFailure(423,"HEAVY_RUNTIME_BUSY");
        if(mapper.acquireHeavy(item.buildId(),item.generation(),owner)!=1)throw new FileFailure(423,"HEAVY_RUNTIME_BUSY");
        var acquired=mapper.heavy();if(acquired==null||!owner.equals(acquired.ownerId()))throw new FileFailure(409,"KNOWLEDGE_SLOT_LOST");
        if(!inbox&&mapper.insertInbox(item)==0)throw new FileFailure(409,"KNOWLEDGE_DUPLICATE_RACE");
        if(mapper.claim(item,owner,acquired.fence())!=1)throw new FileFailure(409,"KNOWLEDGE_CLAIM_REJECTED");
        BuildCommand command=mapper.command(item.buildId());if(command==null)throw new FileFailure(409,"KNOWLEDGE_CLAIM_UNCONFIRMED");return command;
    });}
    @Override public void insertChunk(BuildCommand c,Chunk chunk){tx.executeWithoutResult(s->{guard(c);int changed=mapper.insertChunk(c,chunk);
        if(changed==0&&!mapper.chunkMatches(c,chunk))throw new FileFailure(409,"KNOWLEDGE_CHUNK_CONFLICT");
        if(mapper.progress(c,chunk.ordinal()+1)!=1)throw new FileFailure(409,"KNOWLEDGE_BUILD_FENCE_LOST");});}
    @Override public void complete(BuildCommand c,int count,String manifest){tx.executeWithoutResult(s->{guard(c);
        if(mapper.commitIndex(c,count,manifest)!=1)throw new FileFailure(409,"KNOWLEDGE_INDEX_COMMIT_REJECTED");
        mapper.retirePrevious(c);if(mapper.commitDocument(c)!=1||mapper.completeBuild(c)!=1)throw new FileFailure(409,"KNOWLEDGE_INDEX_COMMIT_REJECTED");
        if(mapper.releaseHeavy(c)!=1)throw new FileFailure(409,"KNOWLEDGE_SLOT_RELEASE_REJECTED");
    });}
    @Override public void fail(BuildCommand c,String code){tx.executeWithoutResult(s->{
        mapper.failIndex(c);mapper.failDocument(c);if(mapper.failBuild(c,stable(code))!=1||mapper.releaseHeavy(c)!=1)throw new FileFailure(409,"KNOWLEDGE_BUILD_FENCE_LOST");});}
    @Override public long acquireQuerySlot(Actor actor,UUID query,UUID owner){return tx.execute(s->{
        requireActor(actor);Long fence=mapper.acquireQueryHeavy(actor,query,owner);return fence==null?-1L:fence;
    });}
    @Override public boolean querySlotCurrent(Actor actor,UUID query,UUID owner,long fence){return mapper.queryHeavyCurrent(actor,query,owner,fence);}
    @Override public void releaseQuerySlot(UUID query,UUID owner,long fence){tx.executeWithoutResult(s->mapper.releaseQueryHeavy(query,owner,fence));}
    @Override public List<Hit> search(Actor actor,List<UUID> bases,String query,String vector,int limit){
        if(!mapper.authorized(actor))throw new FileFailure(403,"KNOWLEDGE_ACCESS_DENIED");
        if(bases==null||bases.isEmpty()||bases.stream().anyMatch(base->mapper.base(actor,base)==null))throw new FileFailure(404,"KNOWLEDGE_BASE_NOT_FOUND");
        return mapper.search(actor,bases,query,terms(query),vector,limit);
    }
    private Base replayBase(Actor a,KnowledgeMapper.KnowledgeMutation r,String action,String hash){requireReplay(r,action,hash);return required(mapper.base(a,r.resourceId()),"KNOWLEDGE_BASE_NOT_FOUND");}
    private Document replayDocument(Actor a,KnowledgeMapper.KnowledgeMutation r,String action,String hash){requireReplay(r,action,hash);return required(mapper.document(a,r.resourceId()),"KNOWLEDGE_DOCUMENT_NOT_FOUND");}
    private static void requireReplay(KnowledgeMapper.KnowledgeMutation r,String action,String hash){if(!r.action().equals(action)||!r.requestHash().equals(hash))throw new FileFailure(409,"IDEMPOTENCY_KEY_REUSED");}
    private void require(Actor a){requireActor(a);}
    private void requireActor(Actor a){if(a==null||!mapper.authorized(a))throw new FileFailure(403,"KNOWLEDGE_ACCESS_DENIED");}
    private static <T>T required(T value,String code){if(value==null)throw new FileFailure(404,code);return value;}
    private static String stable(String code){return code!=null&&code.matches("[A-Z0-9_]{1,64}")?code:"KNOWLEDGE_BUILD_FAILED";}
    private static List<String> terms(String query){return java.util.Arrays.stream(query.split("[\\s,，。！？；：、]+" )).filter(s->s.codePointCount(0,s.length())>=2).distinct().limit(20).toList();}
}
