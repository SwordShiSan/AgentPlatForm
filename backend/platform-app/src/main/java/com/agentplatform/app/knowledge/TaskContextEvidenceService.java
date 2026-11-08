package com.agentplatform.app.knowledge;

import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.harness.HarnessPorts.*;
import com.agentplatform.domain.knowledge.KnowledgeData.*;
import com.agentplatform.domain.knowledge.KnowledgeEncoder;
import com.agentplatform.domain.knowledge.KnowledgeRepository;
import com.agentplatform.domain.knowledge.MemoryRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Protected-profile task context source adapter. It only recalls live, actor-owned sources. */
public final class TaskContextEvidenceService implements MemoryPort, RetrievalPort, EvidenceValidationPort {
    private final MemoryRepository memories;
    private final KnowledgeRepository knowledge;
    private final KnowledgeEncoder encoder;

    public TaskContextEvidenceService(MemoryRepository memories, KnowledgeRepository knowledge, KnowledgeEncoder encoder) {
        this.memories=Objects.requireNonNull(memories);this.knowledge=Objects.requireNonNull(knowledge);this.encoder=encoder;
    }

    @Override public CompletionStage<EvidenceResult> recall(com.agentplatform.types.harness.HarnessData.Scope scope) {
        var actor=actor(scope);
        UUID workspace=workspace(scope);
        var references=memories.recall(actor,workspace,10).stream()
                .filter(memory->"ACTIVE".equals(memory.status()))
                .map(memory->new EvidenceReference(memory.workspaceId()==null?"PERSONAL_MEMORY":"WORKSPACE_MEMORY",memory.id().toString(),Long.toString(memory.revision()),
                        sha(memory.content()),memory.category()+"/"+memory.topicKey(),clip(memory.content(),1000),false)).toList();
        return CompletableFuture.completedFuture(new EvidenceResult(references,false,null));
    }

    @Override public CompletionStage<EvidenceResult> search(com.agentplatform.types.harness.HarnessData.Scope scope,String query) {
        if(query==null||query.isBlank())return CompletableFuture.completedFuture(new EvidenceResult(List.of(),false,null));
        var actor=actor(scope);
        List<UUID> bases=knowledge.bases(actor).stream().filter(base->base.workspaceId()==null&&"ACTIVE".equals(base.state()))
                .map(Base::id).limit(20).toList();
        if(bases.isEmpty())return CompletableFuture.completedFuture(new EvidenceResult(List.of(),false,null));

        UUID queryId=null,ownerId=null;long fence=-1;String vector=null,degradation=null;
        try {
            if(encoder!=null) {
                queryId=UUID.randomUUID();ownerId=UUID.randomUUID();fence=knowledge.acquireQuerySlot(actor,queryId,ownerId);
                if(fence<1)degradation="SEMANTIC_ENCODER_BUSY";
                else {
                    try{vector=vector(encoder.embed(query,true));}
                    catch(ChatFailure invalid){throw invalid;}
                    catch(RuntimeException unavailable){degradation="SEMANTIC_ENCODER_UNAVAILABLE";}
                    if(!knowledge.querySlotCurrent(actor,queryId,ownerId,fence))throw new ChatFailure(409,"KNOWLEDGE_SLOT_LOST");
                }
            } else degradation="SEMANTIC_ENCODER_UNAVAILABLE";
            List<Hit> hits=knowledge.search(actor,bases,query,vector,8);
            if(fence>0&&!knowledge.querySlotCurrent(actor,queryId,ownerId,fence))throw new ChatFailure(409,"KNOWLEDGE_SLOT_LOST");
            List<EvidenceReference> references=hits.stream().map(hit->new EvidenceReference("KNOWLEDGE",
                    hit.documentId().toString(),hit.indexVersion().toString(),hit.sourceSha256(),hit.locationJson(),clip(hit.body(),1000),false)).toList();
            return CompletableFuture.completedFuture(new EvidenceResult(references,degradation!=null,degradation));
        } finally {
            if(fence>0)knowledge.releaseQuerySlot(queryId,ownerId,fence);
        }
    }

    @Override public void validate(com.agentplatform.types.harness.HarnessData.Scope scope,List<EvidenceReference> references) {
        if(references.isEmpty())return;
        var actor=actor(scope);
        var memoryRefs=references.stream().filter(ref->"PERSONAL_MEMORY".equals(ref.sourceKind())||"WORKSPACE_MEMORY".equals(ref.sourceKind())).toList();
        if(!memoryRefs.isEmpty()) {
            var current=memories.recall(actor,workspace(scope),100).stream().collect(java.util.stream.Collectors.toMap(m->m.id().toString(),m->m,(first,ignored)->first));
            for(var ref:memoryRefs) {
                var value=current.get(ref.sourceId());
                if(value==null||!"ACTIVE".equals(value.status())||!Long.toString(value.revision()).equals(ref.sourceVersion())||!sha(value.content()).equals(ref.contentSha256()))
                    throw new ChatFailure(403,"CONTEXT_SOURCE_REVOKED");
            }
        }
        for(var ref:references)if("KNOWLEDGE".equals(ref.sourceKind())) {
            UUID id;
            try{id=UUID.fromString(ref.sourceId());}catch(IllegalArgumentException invalid){throw new ChatFailure(403,"CONTEXT_SOURCE_REVOKED");}
            var document=knowledge.document(actor,id);
            if(document==null||!"READY".equals(document.state())&&!"BUILDING".equals(document.state())
                    ||document.activeIndexVersion()==null||!document.activeIndexVersion().toString().equals(ref.sourceVersion())
                    ||!document.sourceSha256().equals(ref.contentSha256()))throw new ChatFailure(403,"CONTEXT_SOURCE_REVOKED");
        }
    }

    private static Actor actor(com.agentplatform.types.harness.HarnessData.Scope scope) {
        return new Actor(UUID.fromString(scope.actorId()),UUID.fromString(scope.spaceId()));
    }
    private static UUID workspace(com.agentplatform.types.harness.HarnessData.Scope scope) {
        return scope.workspaceId()==null?null:UUID.fromString(scope.workspaceId());
    }
    private static String vector(float[] values) {
        if(values==null||values.length!=384)throw new ChatFailure(503,"KNOWLEDGE_VECTOR_INVALID");
        var text=new StringBuilder("[");for(int i=0;i<values.length;i++){if(!Float.isFinite(values[i]))throw new ChatFailure(503,"KNOWLEDGE_VECTOR_INVALID");if(i>0)text.append(',');text.append(Float.toString(values[i]));}return text.append(']').toString();
    }
    private static String clip(String value,int max) {int count=value.codePointCount(0,value.length());return count<=max?value:value.substring(0,value.offsetByCodePoints(0,max));}
    private static String sha(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}
