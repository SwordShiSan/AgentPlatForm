package com.agentplatform.app.files;

import com.agentplatform.domain.chat.ChatRepository;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.domain.chat.WorkspaceTools;
import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import com.agentplatform.types.harness.HarnessData.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Production file tools reuse the public file authority and its reliable MQ/runner path. */
public final class WorkspaceFileToolService {
    public static final int RESULT_BYTES=8192;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ChatRepository chat;
    private final FileRepository files;
    private final AssetStore assets;
    private final FileApplicationService transfer;
    private final TransactionTemplate transactions,nested;
    public WorkspaceFileToolService(ChatRepository chat,FileRepository files,AssetStore assets,TransactionTemplate transactions) {
        this.chat=chat;this.files=files;this.assets=assets;this.transfer=new FileApplicationService(files,assets);
        this.transactions=transactions;this.nested=new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }
    private static Actor actor(Lease l){return new Actor(l.userId(),l.spaceId());}
    private static com.agentplatform.api.files.FileApi.Caller caller(Lease l){return new com.agentplatform.api.files.FileApi.Caller(l.userId().toString(),l.spaceId().toString());}
    private static <T>T args(Call call,Class<T> type){return JSON.readValue(JSON.writeValueAsString(call.arguments()),type);}
    private static String text(Object value){String result=JSON.writeValueAsString(value);if(result.getBytes(StandardCharsets.UTF_8).length>RESULT_BYTES)throw new FileFailure(413,"TOOL_RESULT_LIMIT");return result;}
    private static Observation known(Call call,Object value){return new Observation(call.id(),call.name(),text(value),OperationStatus.SUCCEEDED);}
    private static Observation failed(Call call,String code){return new Observation(call.id(),call.name(),text(Map.of("code",code)),OperationStatus.FAILED);}
    /** Locks quota/owner before file admission; a known pre-effect conflict rolls back only its savepoint. */
    public void admit(Lease lease,Call call) {
        if(!Set.of("workspace_list","workspace_read","workspace_write").contains(call.name()))throw new FileFailure(422,"FILE_TOOL_NOT_SUPPORTED");
        transactions.executeWithoutResult(status->{
            chat.startTool(lease,call);
            if(!"workspace_write".equals(call.name()))return;
            var intent=chat.workspaceToolIntent(lease,call);var request=args(call,WorkspaceTools.WriteFile.class);
            byte[] bytes=request.text().getBytes(StandardCharsets.UTF_8);
            var upload=new UploadRequest(request.path(),bytes.length,RunnerSignatures.sha256(bytes),request.fileId()==null?null:UUID.fromString(request.fileId()),
                request.baseVersionId()==null?null:UUID.fromString(request.baseVersionId()),request.revision());
            String hash=RunnerSignatures.sha256((intent.workspaceId()+"\n"+JSON.writeValueAsString(upload)).getBytes(StandardCharsets.UTF_8));
            try {
                var created=nested.execute(savepoint->files.create(actor(lease),intent.workspaceId(),intent.operationId(),hash,upload));
                chat.recordWorkspaceFileAdmission(lease,call,Objects.requireNonNull(created).id(),null);
            } catch(FileFailure conflict) {
                if(!Set.of(400,409,412,413,423,428).contains(conflict.status()))throw conflict;
                chat.recordWorkspaceFileAdmission(lease,call,null,conflict.getMessage());
            }
        });
    }
    private void check(Lease lease,BooleanSupplier cancelled){if(cancelled.getAsBoolean())throw new FileFailure(409,"TOOL_CANCEL_REQUESTED");chat.check(lease);}
    public Observation run(Lease lease,Call call,BooleanSupplier cancelled) throws IOException,InterruptedException {
        check(lease,cancelled);var intent=chat.workspaceToolIntent(lease,call);
        return switch(call.name()) {
            case "workspace_list" -> list(lease,call,intent.workspaceId(),cancelled);
            case "workspace_read" -> read(lease,call,intent.workspaceId(),cancelled);
            case "workspace_write" -> write(lease,call,cancelled);
            default -> throw new FileFailure(422,"FILE_TOOL_NOT_SUPPORTED");
        };
    }
    private Observation list(Lease lease,Call call,UUID workspace,BooleanSupplier cancelled) {
        var request=args(call,WorkspaceTools.ListFiles.class);
        var page=transfer.list(caller(lease),workspace.toString(),request.parent(),request.cursor(),request.limit());
        var items=new ArrayList<com.agentplatform.api.files.FileApi.FileView>();String next=page.nextCursor();
        for(var item:page.items()) {
            items.add(item);
            String cursor=Base64.getUrlEncoder().withoutPadding().encodeToString(item.path().getBytes(StandardCharsets.UTF_8));
            if(JSON.writeValueAsBytes(Map.of("items",items,"nextCursor",cursor)).length>RESULT_BYTES) {
                items.removeLast();
                if(items.isEmpty())return failed(call,"FILE_METADATA_TOO_LARGE");
                next=Base64.getUrlEncoder().withoutPadding().encodeToString(items.getLast().path().getBytes(StandardCharsets.UTF_8));break;
            }
        }
        check(lease,cancelled);var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",next);return known(call,result);
    }
    private Observation read(Lease lease,Call call,UUID workspace,BooleanSupplier cancelled) throws IOException {
        var request=args(call,WorkspaceTools.ReadFile.class);var file=files.file(actor(lease),UUID.fromString(request.fileId()));
        if(!workspace.equals(file.workspaceId()))throw new FileFailure(404,"RESOURCE_NOT_FOUND");
        var version=files.version(actor(lease),file.id(),UUID.fromString(request.versionId()));
        StringBuilder selected=new StringBuilder();long points=0;char high=0;
        try(var content=assets.open(version.objectKey(),null,null)) {
            if(content.bytes()!=version.sizeBytes()||content.totalBytes()!=version.sizeBytes())throw new IOException("ASSET_IDENTITY_MISMATCH");
            var checked=new AuthorizedAssetStream(content.stream(),version.sizeBytes(),version.sha256(),()->check(lease,cancelled));
            var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            var reader=new InputStreamReader(checked,decoder);char[] buffer=new char[4096];int count;
            // Validate the entire immutable object/hash without retaining the entire file.
            while((count=reader.read(buffer))!=-1){
                // The integrity stream performs timed/byte-gated live DB checks;
                // do not issue a transaction for every 4KiB decoder buffer.
                if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new FileFailure(409,"TOOL_CANCEL_REQUESTED");
                for(int index=0;index<count;index++) {
                    char value=buffer[index];int cp;
                    if(high!=0){if(!Character.isLowSurrogate(value))throw new IOException("FILE_TEXT_ENCODING_INVALID");cp=Character.toCodePoint(high,value);high=0;}
                    else if(Character.isHighSurrogate(value)){high=value;continue;}
                    else {if(Character.isLowSurrogate(value))throw new IOException("FILE_TEXT_ENCODING_INVALID");cp=value;}
                    if(points>=request.offsetCodePoints()&&points-request.offsetCodePoints()<request.maxCodePoints())selected.appendCodePoint(cp);
                    points++;
                }
            }
            if(high!=0)throw new IOException("FILE_TEXT_ENCODING_INVALID");
        }
        check(lease,cancelled);if(request.offsetCodePoints()>points)return failed(call,"FILE_TEXT_OFFSET_INVALID");
        var result=new LinkedHashMap<String,Object>();result.put("fileId",file.id());result.put("versionId",version.id());result.put("sha256",version.sha256());
        while(true) {
            long end=request.offsetCodePoints()+selected.codePointCount(0,selected.length());result.put("text",selected.toString());result.put("nextOffsetCodePoints",end<points?end:null);
            if(JSON.writeValueAsBytes(result).length<=RESULT_BYTES)return known(call,result);
            selected.setLength(selected.offsetByCodePoints(selected.length(),-1));
        }
    }
    private Observation write(Lease lease,Call call,BooleanSupplier cancelled) throws InterruptedException {
        var admission=chat.workspaceFileAdmission(lease,call);
        if(admission==null)throw new FileFailure(409,"TOOL_ADMISSION_MISSING");
        if(admission.failureCode()!=null)return failed(call,admission.failureCode());
        var request=args(call,WorkspaceTools.WriteFile.class);var upload=files.upload(actor(lease),admission.uploadId());
        if("CREATED".equals(upload.status())){
            byte[] content=request.text().getBytes(StandardCharsets.UTF_8);check(lease,cancelled);
            transfer.transfer(caller(lease),upload.id().toString(),content.length,new ByteArrayInputStream(content));
        }
        if(admission.fileOperationId()==null) {
            transactions.executeWithoutResult(status->{
                check(lease,cancelled);var operation=files.completeUpload(actor(lease),admission.uploadId());chat.bindWorkspaceFileOperation(lease,call,operation.id());
            });
        }
        var current=chat.workspaceFileAdmission(lease,call);
        while(true) {
            check(lease,cancelled);var operation=files.operation(actor(lease),current.fileOperationId());
            switch(operation.status()) {
                case "COMMITTED" -> {
                    var confirmed=files.upload(actor(lease),current.uploadId());
                    var file=files.file(actor(lease),confirmed.fileId());var version=files.version(actor(lease),file.id(),confirmed.versionId());
                    check(lease,cancelled);return known(call,Map.of("fileId",file.id(),"versionId",version.id(),"revision",file.revision(),"sha256",version.sha256(),"bytes",version.sizeBytes()));
                }
                case "FAILED" -> {return failed(call,operation.errorCode()==null?"FILE_WRITE_FAILED":operation.errorCode());}
                case "UNKNOWN" -> {return new Observation(call.id(),call.name(),text(Map.of("code","FILE_RESULT_UNKNOWN")),OperationStatus.UNKNOWN);}
                default -> Thread.sleep(100);
            }
        }
    }
}
