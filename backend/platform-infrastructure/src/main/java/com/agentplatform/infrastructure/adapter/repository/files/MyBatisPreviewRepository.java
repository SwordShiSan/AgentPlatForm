package com.agentplatform.infrastructure.adapter.repository.files;

import com.agentplatform.domain.files.FileData.Actor;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.domain.preview.*;
import com.agentplatform.domain.preview.PreviewData.*;
import static com.agentplatform.domain.preview.PreviewData.MAX_BYTES;
import com.agentplatform.infrastructure.dao.files.*;
import com.agentplatform.infrastructure.dao.po.PreviewRows.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import com.agentplatform.types.Uuid7;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

/** Preview facts use quota -> shared heavy gate -> preview -> attempt lock order. */
public final class MyBatisPreviewRepository implements PreviewRepository {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final PreviewMapper mapper;
    private final FileMapper files;
    private final String image,bundle,policy;
    public MyBatisPreviewRepository(PreviewMapper mapper,FileMapper files,String image,String bundle,String policy) {
        this.mapper=mapper;this.files=files;this.image=image;this.bundle=bundle;this.policy=policy;
    }
    private static void tx(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Preview transaction required");}
    private static void one(int count){if(count!=1)throw new FileFailure(409,"PREVIEW_CONCURRENT_CHANGE");}
    private static FileFailure stale(){return new FileFailure(409,"PREVIEW_STALE_OWNER");}
    private boolean allowed(Actor actor){tx();return mapper.authorized(actor);}
    private void authorize(Actor actor){if(!allowed(actor))throw new FileFailure(403,"PREVIEW_AUTHORIZATION_REVOKED");}
    private void commitGuard(Runnable action){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){action.run();}});}
    private Source source(Actor actor,UUID file,UUID version){authorize(actor);var source=mapper.source(actor,file,version);if(source==null)throw new FileFailure(404,"RESOURCE_NOT_FOUND");return source;}
    private void storage(UUID space){tx();if(files.lockStorage(space)==null)throw new FileFailure(409,"SPACE_STORAGE_UNAVAILABLE");}
    private static String sourceFormat(String name) {
        String suffix=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        return switch(suffix) {
            case "pdf"->"PDF";case "docx"->"DOCX";case "xlsx"->"XLSX";case "pptx"->"PPTX";
            case "png"->"PNG";case "jpg","jpeg"->"JPEG";case "gif"->"GIF";case "webp"->"WEBP";
            case "html","htm"->"HTML";case "svg"->"SVG";
            case "txt","md","markdown","json","xml","csv","tsv","java","py","js","ts","css","sh","yaml","yml","properties","log","sql","toml","ini","c","h","cpp","rs","go",""->"TEXT";
            default->throw new FileFailure(415,"PREVIEW_FORMAT_UNSUPPORTED");
        };
    }
    private static void format(String source,String requested,long bytes) {
        if(!Set.of("AUTO","PDF","IMAGE","HTML_STATIC").contains(requested))throw new FileFailure(400,"INVALID_PREVIEW_FORMAT");
        if(!"AUTO".equals(requested)&&!("PDF".equals(requested)&&Set.of("PDF","DOCX","XLSX","PPTX").contains(source))
                &&!("IMAGE".equals(requested)&&Set.of("PNG","JPEG","GIF","WEBP").contains(source))
                &&!("HTML_STATIC".equals(requested)&&"HTML".equals(source)))throw new FileFailure(415,"PREVIEW_FORMAT_UNSUPPORTED");
        if(Set.of("TEXT","HTML","SVG").contains(source)&&bytes>2097152)throw new FileFailure(413,"PREVIEW_TEXT_TOO_LARGE");
    }
    private static long reservation(String source,String requested){return Set.of("PDF","TEXT","SVG").contains(source)||"HTML".equals(source)&&!"HTML_STATIC".equals(requested)?0:MAX_BYTES;}
    private static String key(UUID space,UUID asset,UUID id){return "spaces/"+space+"/preview/"+asset+"/"+id+"/"+Uuid7.next();}
    private Job owned(Actor actor,UUID id,boolean lock) {
        authorize(actor);var row=lock?mapper.lockJob(id):mapper.job(id);
        if(row==null||!row.spaceId().equals(actor.spaceId()))throw new FileFailure(404,"RESOURCE_NOT_FOUND");
        source(actor,row.fileId(),row.versionId());return row;
    }
    @Override public Job create(Actor actor,UUID file,UUID version,String requested,UUID request,String hash) {
        storage(actor.spaceId());var source=source(actor,file,version);
        var prior=mapper.request(actor,request);
        if(prior!=null){if(!hash.equals(prior.requestHash())||!"CREATE".equals(prior.kind()))throw new FileFailure(409,"IDEMPOTENCY_CONFLICT");return owned(actor,prior.previewId(),false);}
        if(!Set.of("AUTO","PDF","IMAGE","HTML_STATIC").contains(requested))throw new FileFailure(400,"INVALID_PREVIEW_FORMAT");
        var cached=mapper.cached(actor.spaceId(),version,bundle,policy,requested);
        if(cached!=null){mapper.insertRequest(actor,request,hash,cached.id(),"CREATE");return cached;}
        String sourceFormat=sourceFormat(source.name());format(sourceFormat,requested,source.bytes());
        if("AUTO".equals(requested))requested=switch(sourceFormat){case "PDF","DOCX","XLSX","PPTX"->"PDF";case "PNG","JPEG","GIF","WEBP"->"IMAGE";default->"AUTO";};
        UUID id=Uuid7.next(),asset=Uuid7.next();
        var row=new Job(id,actor.spaceId(),source.workspaceId(),actor.userId(),file,version,source.name(),requested,sourceFormat,
                source.objectKey(),source.bytes(),source.sha256(),image,bundle,policy,"QUEUED",1,1,Uuid7.next(),null,null,null,null,0,
                null,key(actor.spaceId(),asset,id),null,null,null,null,null,false,asset);
        long reserved=reservation(sourceFormat,requested);
        one(mapper.reserveStorage(actor.spaceId(),reserved));mapper.insert(row);mapper.reserve(id,actor.spaceId(),reserved);
        mapper.insertRequest(actor,request,hash,id,"CREATE");mapper.outbox(Uuid7.next(),row);event(row);
        commitGuard(()->authorize(actor));return row;
    }
    @Override public Job get(Actor actor,UUID id){return owned(actor,id,false);}
    @Override public Job retry(Actor actor,UUID id,long expected,UUID request,String hash) {
        storage(actor.spaceId());mapper.lockHeavy();var row=owned(actor,id,true);var prior=mapper.request(actor,request);
        if(prior!=null){if(!hash.equals(prior.requestHash())||!prior.previewId().equals(id)||!"RETRY".equals(prior.kind()))throw new FileFailure(409,"IDEMPOTENCY_CONFLICT");return row;}
        if(row.revision()!=expected)throw new FileFailure(412,"PREVIEW_VERSION_CONFLICT");
        if(!"FAILED".equals(row.state())||!row.retryable())throw new FileFailure(409,"PREVIEW_RETRY_NOT_ALLOWED");
        var attempt=mapper.attempt(row.commandId());if(attempt==null||attempt.receiptJson()==null)throw new FileFailure(409,"PREVIEW_STOP_UNCONFIRMED");
        long reserved=reservation(row.sourceFormat(),row.requestedFormat());one(mapper.reserveStorage(row.spaceId(),reserved));
        one(mapper.reservationState(id,"RESERVED"));reset(row);mapper.insertRequest(actor,request,hash,id,"RETRY");
        var next=mapper.job(id);mapper.outbox(Uuid7.next(),next);event(next);commitGuard(()->authorize(actor));return next;
    }
    private void reset(Job row){UUID asset=Uuid7.next();one(mapper.retry(row.id(),Uuid7.next(),asset,key(row.spaceId(),asset,row.id())));}
    private void event(Job row){mapper.event(Uuid7.next(),row);}
    @Override public List<Publication> publications(UUID owner){tx();return mapper.publications(owner);}
    @Override public void published(Publication item,boolean confirmed){tx();mapper.published(item,confirmed);}
    @Override public Command claim(Publication item,UUID owner) {
        tx();if(!mapper.knownPublication(item))throw new FileFailure(409,"PREVIEW_DELIVERY_UNKNOWN");
        storage(item.spaceId());var heavy=mapper.lockHeavy();var row=mapper.lockJob(item.previewId());
        if(row==null||!row.spaceId().equals(item.spaceId())||row.generation()!=item.generation()||!"QUEUED".equals(row.state())){mapper.inbox(item);return null;}
        var actor=new Actor(row.actorId(),row.spaceId());
        if(!allowed(actor)||mapper.source(actor,row.fileId(),row.versionId())==null) {
            one(mapper.rejectQueued(row.id()));one(mapper.releaseStorage(row.id()));one(mapper.reservationState(row.id(),"RELEASED"));mapper.inbox(item);event(mapper.job(row.id()));return null;
        }
        if(heavy.kind()!=null){mapper.defer(item);return null;}
        long fence=heavy.fence()+1;one(mapper.acquireHeavy(row.id(),row.generation(),owner,fence));one(mapper.claim(row.id(),owner,fence));
        var claimed=mapper.job(row.id());var command=Command.freeze(claimed);mapper.insertAttempt(command,JSON.writeValueAsString(command));mapper.inbox(item);event(claimed);
        commitGuard(()->guard(command));return command;
    }
    private Attempt matching(Command command,boolean lock) {
        tx();var attempt=lock?mapper.lockAttempt(command.commandId()):mapper.attempt(command.commandId());
        if(attempt==null||!command.equals(JSON.readValue(attempt.commandJson(),Command.class)))throw stale();return attempt;
    }
    @Override public void guard(Command command) {matching(command,false);source(new Actor(command.actorId(),command.spaceId()),command.fileId(),command.versionId());if(!mapper.live(command))throw stale();}
    @Override public void renew(Command command){guard(command);one(mapper.renew(command));commitGuard(()->guard(command));}
    @Override public void beforeCall(Command command){guard(command);commitGuard(()->guard(command));}
    @Override public boolean runnerClaim(Command command,UUID runner) {guard(command);var attempt=matching(command,true);if(!"CLAIMED".equals(attempt.status()))return false;boolean first=mapper.runnerClaim(command,runner)==1;commitGuard(()->guard(command));return first;}
    @Override public boolean runnerObserveNeverAccepted(Command command,UUID observer) {
        var attempt=matching(command,true);
        if(attempt.runnerId()!=null)return observer.equals(attempt.runnerId());
        return mapper.observeNeverAccepted(command,observer)==1;
    }
    @Override public Command runnerLookup(UUID id){tx();var job=mapper.job(id);if(job==null)return null;var attempt=mapper.attempt(job.commandId());return attempt==null?null:JSON.readValue(attempt.commandJson(),Command.class);}
    private Attempt runner(Command command,UUID runner){var row=matching(command,true);if(!runner.equals(row.runnerId()))throw stale();return row;}
    @Override public PreviewRuntime.Physical runnerPhysical(Command command,UUID runner){var row=runner(command,runner);return new PreviewRuntime.Physical(row.containerId(),row.startedAt());}
    @Override public boolean runnerStartWasRequested(Command command,UUID runner){return runner(command,runner).startRequested();}
    @Override public void runnerCreated(Command command,UUID runner,String container){runnerGuard(command,runner);if(container==null||!container.matches("[a-f0-9]{64}"))throw new FileFailure(409,"PREVIEW_PHYSICAL_ID_INVALID");one(mapper.physicalCreated(command,runner,container));commitGuard(()->guard(command));}
    @Override public void runnerStartRequested(Command command,UUID runner){runnerGuard(command,runner);one(mapper.physicalStartRequested(command,runner));commitGuard(()->guard(command));}
    @Override public void runnerStarted(Command command,UUID runner,String started){runnerGuard(command,runner);Instant.parse(started);one(mapper.physicalStarted(command,runner,started));commitGuard(()->guard(command));}
    @Override public void runnerObservedPhysical(Command command,UUID runner,String container,String started,boolean requested) {
        var row=runner(command,runner);
        if(container==null||!container.matches("[a-f0-9]{64}")||started!=null&&!requested)throw new FileFailure(409,"PREVIEW_PHYSICAL_ID_INVALID");
        if(started!=null)Instant.parse(started);
        if(row.containerId()!=null&&!row.containerId().equals(container)||row.startedAt()!=null&&(started==null||!Instant.parse(row.startedAt()).equals(Instant.parse(started))))
            throw new FileFailure(409,"PREVIEW_PHYSICAL_ID_CONFLICT");
        if(row.receiptJson()!=null)return;
        one(mapper.physicalObserved(command,runner,container,started,requested));
    }
    @Override public void runnerExecCreated(Command command,UUID runner,String exec) {
        var row=runner(command,runner);
        if(exec==null||!exec.matches("[a-f0-9]{64}")||row.containerId()==null||!row.startRequested()||row.startedAt()==null||row.receiptJson()!=null)
            throw new FileFailure(409,"PREVIEW_READ_EXEC_INVALID");
        if(exec.equals(row.execId()))return;
        one(mapper.insertExec(command,exec));one(mapper.execCreated(command,runner,exec));
    }
    @Override public void runnerGuard(Command command,UUID runner){runner(command,runner);guard(command);}
    @Override public void recordSourceTransferClosed(Command command,UUID runner){runner(command,runner);one(mapper.sourceClosed(command,runner));mapper.releaseSource(command,runner,RunnerSignatures.sha256(command.sourceObjectKey().getBytes(StandardCharsets.UTF_8)));}
    @Override public Receipt receipt(Command command){matching(command,false);var row=mapper.attempt(command.commandId());return row.receiptJson()==null?null:JSON.readValue(row.receiptJson(),Receipt.class);}
    private void validate(Command command,UUID runner,Receipt receipt,Attempt attempt) {
        if(receipt==null||!command.commandId().equals(receipt.commandId())||!command.previewId().equals(receipt.previewId())
                ||command.generation()!=receipt.generation()||command.fence()!=receipt.fence()||!runner.equals(receipt.runnerId())
                ||receipt.stop()==null||!receipt.stop().confirmed()||!command.imageId().equals(receipt.stop().imageId()))throw new FileFailure(409,"PREVIEW_RECEIPT_INVALID");
        var stop=receipt.stop();
        if(attempt.containerId()!=null&&!attempt.containerId().equals(stop.containerId())||attempt.startRequested()&&stop.startedAt()==null
                ||attempt.containerId()==null&&(attempt.startRequested()||stop.containerId()!=null)
                ||attempt.startedAt()!=null&&!Instant.parse(attempt.startedAt()).equals(stop.startedAt())||!Objects.equals(attempt.execId(),stop.execId()))throw new FileFailure(409,"PREVIEW_STOP_IDENTITY_MISMATCH");
        if(!Set.of("READY","FAILED").contains(receipt.state())||receipt.bytes()<0||receipt.bytes()>MAX_BYTES)throw new FileFailure(409,"PREVIEW_RECEIPT_INVALID");
        if("READY".equals(receipt.state())) {
            if(receipt.sha256()==null||!receipt.sha256().matches("[a-f0-9]{64}")||!Set.of("PDF","IMAGE","TEXT","HTML_STATIC").contains(receipt.format())
                    ||!Set.of("application/pdf","image/png","text/plain").contains(receipt.mediaType())||receipt.pages()!=null&&(receipt.pages()<1||receipt.pages()>200))throw new FileFailure(409,"PREVIEW_RECEIPT_INVALID");
            String expected=switch(command.sourceFormat()) {
                case "PDF","DOCX","XLSX","PPTX"->"PDF";case "PNG","JPEG","GIF","WEBP"->"IMAGE";
                case "HTML"->"HTML_STATIC".equals(command.requestedFormat())?"HTML_STATIC":"TEXT";default->"TEXT";
            };
            String mime="PDF".equals(expected)?"application/pdf":"IMAGE".equals(expected)?"image/png":"text/plain";
            if(!expected.equals(receipt.format())||!mime.equals(receipt.mediaType())||"PDF".equals(expected)&&receipt.pages()==null)
                throw new FileFailure(409,"PREVIEW_OUTPUT_FORMAT_MISMATCH");
            if(receipt.sourceReferenced()) {
                if(!command.sourceObjectKey().equals(receipt.objectKey())||!command.sourceSha256().equals(receipt.sha256())||command.sourceBytes()!=receipt.bytes()
                        ||!Set.of("PDF","TEXT","SVG","HTML").contains(command.sourceFormat())||"HTML_STATIC".equals(receipt.format()))throw new FileFailure(409,"PREVIEW_SOURCE_IDENTITY_MISMATCH");
            }else if(!command.outputObjectKey().equals(receipt.objectKey()))throw new FileFailure(409,"PREVIEW_OUTPUT_IDENTITY_MISMATCH");
        } else if(receipt.errorCode()==null||!receipt.errorCode().matches("[A-Z0-9_]{1,64}"))throw new FileFailure(409,"PREVIEW_RECEIPT_INVALID");
    }
    @Override public void runnerReceipt(Command command,UUID runner,Receipt receipt) {
        var attempt=runner(command,runner);validate(command,runner,receipt,attempt);
        if(attempt.receiptJson()!=null){if(!receipt.equals(JSON.readValue(attempt.receiptJson(),Receipt.class)))throw new FileFailure(409,"PREVIEW_RECEIPT_CONFLICT");return;}
        var json=JSON.writeValueAsString(receipt);one(mapper.receipt(command,runner,json,RunnerSignatures.sha256(json.getBytes(StandardCharsets.UTF_8))));
    }
    @Override public void complete(Command command,Receipt receipt) {
        storage(command.spaceId());mapper.lockHeavy();var row=mapper.lockJob(command.previewId());var attempt=matching(command,false);
        if("READY".equals(row.state())||"FAILED".equals(row.state()))return;
        if(row.generation()!=command.generation()||!row.commandId().equals(command.commandId()))throw stale();
        validate(command,attempt.runnerId(),receipt,attempt);
        if(attempt.receiptJson()==null||!receipt.equals(JSON.readValue(attempt.receiptJson(),Receipt.class)))throw new FileFailure(409,"PREVIEW_RECEIPT_NOT_DURABLE");
        var actor=new Actor(command.actorId(),command.spaceId());boolean authorized=allowed(actor)&&mapper.source(actor,command.fileId(),command.versionId())!=null;
        String state=authorized?receipt.state():"FAILED",error=authorized?receipt.errorCode():"PREVIEW_AUTHORIZATION_REVOKED";
        var reservation=mapper.reservation(row.id());
        if("READY".equals(state)) {
            UUID asset;
            if(receipt.sourceReferenced()){asset=source(actor,row.fileId(),row.versionId()).assetId();}
            else {mapper.insertAsset(row,receipt);asset=row.assetId();}
            mapper.reference(row,asset,receipt.sourceReferenced());one(mapper.settleStorage(row.id(),receipt.sourceReferenced()?0:receipt.bytes()));one(mapper.reservationState(row.id(),"SETTLED"));
        } else {one(mapper.releaseStorage(row.id()));one(mapper.reservationState(row.id(),"RELEASED"));}
        one(mapper.project(command,receipt,state,error));one(mapper.releaseHeavy(command));var projected=mapper.job(row.id());event(projected);
        // One automatic infrastructure retry, after a stopped receipt and a complete refund, never UNKNOWN.
        if(authorized&&"FAILED".equals(state)&&receipt.retryable()&&row.attempts()==1) {
            one(mapper.reserveStorage(row.spaceId(),reservation.bytes()));one(mapper.reservationState(row.id(),"RESERVED"));reset(projected);
            var next=mapper.job(row.id());mapper.outbox(Uuid7.next(),next);event(next);
        } else if("READY".equals(state))commitGuard(()->authorize(actor));
    }
    @Override public void unknown(Command command,String code){matching(command,false);mapper.unknown(command,code);var row=mapper.job(command.previewId());if("BLOCKED".equals(row.state())){mapper.reservationState(row.id(),"RECONCILING");event(row);}}
    @Override public void blockExpired(){tx();mapper.expire();}
    @Override public List<Command> reconciliations(){tx();return mapper.uncertain().stream().map(row->JSON.readValue(mapper.attempt(row.commandId()).commandJson(),Command.class)).toList();}
    @Override public void quarantine(UUID id,String hash){tx();mapper.quarantine(id,hash);}
}
