package com.agentplatform.infrastructure.adapter.repository.files;

import com.agentplatform.domain.attachment.*;
import com.agentplatform.domain.attachment.AttachmentData.*;
import com.agentplatform.domain.files.FileData.Actor;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.infrastructure.dao.files.*;
import com.agentplatform.types.Uuid7;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.UUID;
import org.springframework.transaction.support.*;

public final class MyBatisAttachmentRepository implements AttachmentRepository {
    private final AttachmentMapper mapper;
    private final FileMapper files;
    public MyBatisAttachmentRepository(AttachmentMapper mapper, FileMapper files) { this.mapper=mapper;this.files=files; }
    private static void one(int n) { if(n!=1)throw new FileFailure(409,"ATTACHMENT_CONCURRENT_CHANGE"); }
    private void authorize(Actor actor) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Attachment transaction required");
        if(!mapper.authorized(actor))throw new FileFailure(403,"ATTACHMENT_AUTHORIZATION_REVOKED");
    }
    private void commitGuard(Actor actor) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){authorize(actor);}
        });
    }
    private void storage(Actor actor) { if(files.lockStorage(actor.spaceId())==null)throw new FileFailure(409,"SPACE_STORAGE_UNAVAILABLE");authorize(actor); }
    private Upload owned(Actor actor,UUID id,boolean lock) {
        authorize(actor);var row=mapper.upload(id,lock);
        if(row==null||!actor.spaceId().equals(row.spaceId())||!actor.userId().equals(row.actorId()))throw new FileFailure(404,"RESOURCE_NOT_FOUND");
        return row;
    }
    @Override public Upload create(Actor actor,UUID request,String hash,Create body) {
        storage(actor);
        var prior=mapper.request(actor,request);
        if(prior!=null){if(!hash.equals(prior.requestHash()))throw new FileFailure(409,"IDEMPOTENCY_CONFLICT");return prior;}
        if(body==null||body.draftId()==null||body.filename()==null||body.bytes()<0||body.bytes()>104857600
                ||body.sha256()==null||!body.sha256().matches("[a-f0-9]{64}"))throw new FileFailure(400,"INVALID_ATTACHMENT_REQUEST");
        String name=Normalizer.normalize(body.filename(),Normalizer.Form.NFC);
        if(name.isBlank()||name.getBytes(StandardCharsets.UTF_8).length>1024||name.indexOf('/')>=0||name.indexOf('\\')>=0
                ||name.codePoints().anyMatch(Character::isISOControl))throw new FileFailure(400,"INVALID_ATTACHMENT_FILENAME");
        UUID id=Uuid7.next(),asset=Uuid7.next(),upload=Uuid7.next();
        String key="spaces/"+actor.spaceId()+"/chat-attachment/"+asset+"/"+id+"/"+Uuid7.next();
        var attachment=new Attachment(id,actor.spaceId(),actor.userId(),asset,body.draftId(),null,name,body.bytes(),body.sha256(),"STAGING",1,key);
        one(mapper.reserve(actor.spaceId(),body.bytes()));mapper.insertAsset(attachment);mapper.insertAttachment(attachment);
        var row=new Upload(upload,actor.spaceId(),actor.userId(),id,request,hash,"CREATED",null,null,1,null,name,body.bytes(),body.sha256(),key);
        mapper.insertUpload(row);mapper.insertReservation(row);commitGuard(actor);return mapper.upload(upload,false);
    }
    @Override public Upload upload(Actor actor,UUID id){return owned(actor,id,false);}
    @Override public Upload request(Actor actor,UUID request){authorize(actor);var row=mapper.request(actor,request);if(row==null)throw new FileFailure(404,"RESOURCE_NOT_FOUND");return row;}
    @Override public Upload begin(Actor actor,UUID id,UUID owner){owned(actor,id,true);one(mapper.begin(id,owner));commitGuard(actor);return owned(actor,id,false);}
    @Override public void guard(Actor actor,UUID id,UUID owner){owned(actor,id,false);if(!mapper.live(id,owner))throw new FileFailure(409,"ATTACHMENT_TRANSFER_AUTHORITY_LOST");}
    @Override public void verified(Actor actor,UUID id,UUID owner){guard(actor,id,owner);one(mapper.finishTransfer(id,owner,"VERIFIED"));commitGuard(actor);}
    @Override public void uncertain(Actor actor,UUID id,UUID owner){owned(actor,id,true);one(mapper.finishTransfer(id,owner,"UNKNOWN"));}
    @Override public void rejected(Actor actor,UUID id,UUID owner){owned(actor,id,true);one(mapper.finishTransfer(id,owner,"CREATED"));}
    @Override public Upload recoverVerified(Actor actor,UUID id,long revision){
        authorize(actor);if(!files.lockTransferIdle())return owned(actor,id,false);
        var row=owned(actor,id,true);if(row.revision()==revision&&java.util.Set.of("UNKNOWN","UPLOADING").contains(row.status())){one(mapper.recoverVerified(id,revision));commitGuard(actor);}return owned(actor,id,false);
    }
    @Override public Attachment complete(Actor actor,UUID id) {
        storage(actor);var upload=owned(actor,id,true);var attachment=get(actor,upload.attachmentId());
        if("COMMITTED".equals(upload.status()))return attachment;
        if(!"VERIFIED".equals(upload.status()))throw new FileFailure(409,"ATTACHMENT_CONTENT_NOT_VERIFIED");
        one(mapper.settle(id));one(mapper.reservationCommitted(id));one(mapper.assetAvailable(attachment.assetId()));
        one(mapper.attachmentAvailable(attachment.id()));one(mapper.uploadCommitted(id));commitGuard(actor);return get(actor,attachment.id());
    }
    @Override public Attachment get(Actor actor,UUID id) {
        authorize(actor);var row=mapper.attachment(id,false);
        if(row==null||!actor.spaceId().equals(row.spaceId())||!actor.userId().equals(row.actorId()))throw new FileFailure(404,"RESOURCE_NOT_FOUND");
        return row;
    }
    @Override public Usage usage(Actor actor){authorize(actor);var rows=mapper.usage(actor);if(rows.isEmpty())throw new FileFailure(404,"RESOURCE_NOT_FOUND");var first=rows.getFirst();return new Usage(first.objectLimitBytes(),first.usedBytes(),first.reservedBytes(),first.retainedBytes(),rows.stream().map(r->new Category(r.category(),r.categoryBytes(),r.objects())).toList());}
}
