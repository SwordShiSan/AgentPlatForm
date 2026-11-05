package com.agentplatform.app.attachment;

import com.agentplatform.domain.attachment.AttachmentSource;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.files.FileData.Actor;
import com.agentplatform.infrastructure.dao.files.TaskAttachmentMapper;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import com.agentplatform.types.AttachmentReference;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

/** Joins the existing task transaction; no object transport runs under these locks. */
public final class AttachmentSubmission {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final TaskAttachmentMapper mapper;private final TransactionTemplate transactions;private final AttachmentAdmission admission;
    public AttachmentSubmission(TaskAttachmentMapper mapper,TransactionTemplate transactions,AttachmentAdmission admission){this.mapper=mapper;this.transactions=transactions;this.admission=admission;}
    public <T>T submit(Actor actor,UUID request,String path,String requestBody,String text,UUID conversation,boolean plan,boolean workspace,List<AttachmentReference> refs,Supplier<T> create,Function<T,UUID> taskId){
        var references=refs==null?List.<AttachmentReference>of():List.copyOf(refs);
        if(references.size()>10)throw new ChatFailure(422,"ATTACHMENT_LIMIT_EXCEEDED");
        if(references.stream().map(AttachmentReference::extractionId).distinct().count()!=references.size())throw new ChatFailure(400,"DUPLICATE_ATTACHMENT");
        admission.requireFits(actor,conversation,text,plan,workspace,references);
        String hash=RunnerSignatures.sha256((requestBody+"\n"+JSON.writeValueAsString(references)).getBytes(StandardCharsets.UTF_8));
        return transactions.execute(status->{
            mapper.requestLock(actor.spaceId()+":"+actor.userId()+":"+path+":"+request);
            String prior=mapper.requestHash(actor,path,request);
            if(prior!=null&&!prior.equals(hash))throw new ChatFailure(409,"IDEMPOTENCY_CONFLICT");
            if(prior==null&&!references.isEmpty()&&mapper.legacyRequest(actor,path,request))throw new ChatFailure(409,"IDEMPOTENCY_CONFLICT");
            var sources=references.stream().map(ref->{var source=mapper.source(actor,ref);if(source==null)throw new ChatFailure(422,"ATTACHMENT_NOT_READY_OR_UNAUTHORIZED");return source;}).toList();
            T result=create.get();UUID task=taskId.apply(result);
            if(prior==null){
                for(int i=0;i<sources.size();i++){
                    var source=sources.get(i);if(mapper.bind(actor,task,i,source)!=1)throw new ChatFailure(409,"ATTACHMENT_BINDING_CONFLICT");
                    var block=new LinkedHashMap<String,Object>();block.put("type","FILE_REFERENCE");block.put("blockId",UUID.randomUUID().toString());block.put("sourceKind",source.sourceKind());
                    block.put("extractionId",source.extractionId().toString());block.put("name",source.name());block.put("sourceSha256",source.sourceSha256());block.put("textSha256",source.textSha256());
                    if(source.fileId()!=null){block.put("fileId",source.fileId().toString());block.put("versionId",source.versionId().toString());}
                    if(source.attachmentId()!=null)block.put("attachmentId",source.attachmentId().toString());
                    if(mapper.appendBlock(task,JSON.writeValueAsString(block))!=1)throw new ChatFailure(409,"ATTACHMENT_BINDING_CONFLICT");
                }
                mapper.remember(actor,path,request,hash,task);
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){for(var ref:references)if(mapper.source(actor,ref)==null)throw new ChatFailure(403,"ATTACHMENT_AUTHORIZATION_REVOKED");}});
            return result;
        });
    }
}
