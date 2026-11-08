package com.agentplatform.app.knowledge;

import com.agentplatform.api.knowledge.KnowledgeApi;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.domain.knowledge.KnowledgeData.*;
import com.agentplatform.domain.knowledge.KnowledgeRepository;
import com.agentplatform.types.Uuid7;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.UUID;
import com.agentplatform.domain.knowledge.KnowledgeEncoder;

public final class KnowledgeApplicationService implements KnowledgeApi {
    private final KnowledgeRepository repository;
    private final KnowledgeEncoder encoder;
    public KnowledgeApplicationService(KnowledgeRepository repository, KnowledgeEncoder encoder) { this.repository = repository; this.encoder=encoder; }
    @Override public BaseView createBase(Caller caller, String requestId, CreateBase body) {
        var actor=actor(caller);if(body==null||body.name()==null)throw new FileFailure(400,"INVALID_KNOWLEDGE_BASE");
        String name=Normalizer.normalize(body.name().strip(),Normalizer.Form.NFC);
        if(name.isBlank()||name.getBytes(StandardCharsets.UTF_8).length>512)throw new FileFailure(400,"INVALID_KNOWLEDGE_BASE");
        return view(repository.createBase(actor,name,id(requestId),hash("BASE_CREATE\n"+name)));
    }
    @Override public List<BaseView> bases(Caller caller){return repository.bases(actor(caller)).stream().map(KnowledgeApplicationService::view).toList();}
    @Override public List<DocumentView> documents(Caller caller,String baseId){return repository.documents(actor(caller),id(baseId)).stream().map(KnowledgeApplicationService::view).toList();}
    @Override public DocumentView addDocument(Caller caller,String baseId,String requestId,AddDocument body){
        if(body==null||body.extractionId()==null)throw new FileFailure(400,"EXTRACTION_REQUIRED");
        if(encoder==null)throw new FileFailure(503,"KNOWLEDGE_INDEXING_UNAVAILABLE");
        return view(repository.addDocument(actor(caller),id(baseId),id(body.extractionId()),id(requestId),hash("DOCUMENT_ADD\n"+baseId+"\n"+body.extractionId())));
    }
    @Override public DocumentView rebuild(Caller caller,String documentId,String requestId){
        if(encoder==null)throw new FileFailure(503,"KNOWLEDGE_INDEXING_UNAVAILABLE");
        return view(repository.rebuild(actor(caller),id(documentId),id(requestId),hash("DOCUMENT_REBUILD\n"+documentId)));
    }
    @Override public void deleteDocument(Caller caller,String documentId,String requestId){
        repository.deleteDocument(actor(caller),id(documentId),id(requestId),hash("DOCUMENT_DELETE\n"+documentId));
    }
    @Override public SearchView search(Caller caller,Search body){
        if(body==null||body.query()==null)throw new FileFailure(400,"INVALID_KNOWLEDGE_QUERY");
        String query=Normalizer.normalize(body.query().strip(),Normalizer.Form.NFC);
        if(query.isBlank()||query.getBytes(StandardCharsets.UTF_8).length>8192)throw new FileFailure(400,"INVALID_KNOWLEDGE_QUERY");
        int limit=body.limit()==null?8:body.limit();if(limit<1||limit>20)throw new FileFailure(400,"INVALID_KNOWLEDGE_LIMIT");
        if(body.baseIds()==null||body.baseIds().isEmpty()||body.baseIds().size()>20)throw new FileFailure(400,"KNOWLEDGE_BASE_REQUIRED");
        var actor=actor(caller);List<UUID> bases=body.baseIds().stream().map(KnowledgeApplicationService::id).distinct().toList();
        String vector=null;boolean semantic=false;String degradation=null;
        UUID queryId=null,ownerId=null;long fence=-1;
        try {
            if(encoder!=null){
                queryId=Uuid7.next();ownerId=Uuid7.next();fence=repository.acquireQuerySlot(actor,queryId,ownerId);
                if(fence<1)throw new FileFailure(423,"HEAVY_RUNTIME_BUSY");
                try{vector=vector(encoder.embed(query,true));semantic=true;}
                catch(FileFailure invalid){throw invalid;}
                catch(RuntimeException unavailable){if(body.semanticRequired())throw new FileFailure(503,"KNOWLEDGE_SEMANTIC_UNAVAILABLE");degradation="SEMANTIC_ENCODER_UNAVAILABLE";}
                if(!repository.querySlotCurrent(actor,queryId,ownerId,fence))throw new FileFailure(409,"KNOWLEDGE_SLOT_LOST");
            } else if(body.semanticRequired())throw new FileFailure(503,"KNOWLEDGE_SEMANTIC_UNAVAILABLE");
            else degradation="SEMANTIC_ENCODER_UNAVAILABLE";
            List<Hit> hits=repository.search(actor,bases,query,vector,limit);
            if(fence>0&&!repository.querySlotCurrent(actor,queryId,ownerId,fence))throw new FileFailure(409,"KNOWLEDGE_SLOT_LOST");
            return new SearchView(hits.stream().map(h->new SearchHit(h.documentId().toString(),h.sourceKind(),str(h.versionId()),str(h.attachmentId()),h.name(),clip(h.body(),1000),h.locationJson(),h.score(),h.sourceSha256())).toList(),semantic,degradation);
        } finally {
            if(fence>0)repository.releaseQuerySlot(queryId,ownerId,fence);
        }
    }
    static Actor actor(Caller caller){if(caller==null)throw new FileFailure(401,"AUTHENTICATION_REQUIRED");return new Actor(id(caller.userId()),id(caller.spaceId()));}
    static UUID id(String value){try{return UUID.fromString(value);}catch(RuntimeException invalid){throw new FileFailure(400,"INVALID_UUID");}}
    static String hash(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    static BaseView view(Base b){return new BaseView(b.id().toString(),b.name(),b.state(),b.revision());}
    static DocumentView view(Document d){return new DocumentView(d.id().toString(),d.sourceKind(),str(d.fileId()),str(d.versionId()),str(d.attachmentId()),d.extractionId().toString(),d.name(),d.state(),str(d.activeIndexVersion()),d.revision());}
    private static String str(UUID value){return value==null?null:value.toString();}
    private static String clip(String value,int maxCodePoints){int count=value.codePointCount(0,value.length());return count<=maxCodePoints?value:value.substring(0,value.offsetByCodePoints(0,maxCodePoints));}
    private static String vector(float[] values){if(values==null||values.length!=384)throw new FileFailure(503,"KNOWLEDGE_VECTOR_INVALID");var out=new StringBuilder("[");for(int i=0;i<values.length;i++){if(!Float.isFinite(values[i]))throw new FileFailure(503,"KNOWLEDGE_VECTOR_INVALID");if(i>0)out.append(',');out.append(Float.toString(values[i]));}return out.append(']').toString();}
}
