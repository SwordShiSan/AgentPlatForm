package com.agentplatform.app.files;

import com.agentplatform.api.files.FileRunnerApi;
import com.agentplatform.api.workspace.RunnerControlApi.SignedResult;
import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import com.agentplatform.domain.workspace.RunnerInstance;
import com.agentplatform.domain.workspace.WorkspaceFailure;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import tools.jackson.databind.json.JsonMapper;

/** One durable acceptance permits one physical invocation. GET never invokes another write. */
public final class FileRunnerService implements FileRunnerApi {
    public static final String PATH="/internal/file-operations/v1";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final FileRunnerRepository repository;
    private final FileRuntime runtime;
    private final AssetStore assets;
    private final RunnerSignatures signatures;
    private final RunnerInstance instance;
    private final boolean captureMode;
    private final String path;
    private final Set<UUID> running=ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<UUID,FileRuntime.Result> completed=new ConcurrentHashMap<>();
    private final Set<UUID> unconfirmedTransports=ConcurrentHashMap.newKeySet();
    public FileRunnerService(FileRunnerRepository repository,FileRuntime runtime,AssetStore assets,RunnerSignatures signatures) {
        this(repository,runtime,assets,signatures,false);
    }
    FileRunnerService(FileRunnerRepository repository,FileRuntime runtime,AssetStore assets,RunnerSignatures signatures,boolean captureMode) {
        this.repository=repository;this.runtime=runtime;this.assets=assets;this.signatures=signatures;this.instance=runtime.runnerInstance();
        this.captureMode=captureMode;this.path=captureMode?"/internal/file-captures/v1":PATH;
        repository.register(instance);
    }
    public static RunnerSignatures.Binding binding(Command command,String method,String path) {
        var operation=command.operation();
        return new RunnerSignatures.Binding(method,path,operation.commandId(),operation.id(),operation.spaceId(),operation.workspaceId(),operation.generation(),operation.fence());
    }
    private SignedResult signed(Command command,Receipt receipt,String path) {
        byte[] body=JSON.writeValueAsBytes(receipt);
        if(body.length>65536)throw new FileFailure(503,"FILE_RECEIPT_TOO_LARGE");
        return new SignedResult(body,signatures.sign(binding(command,"RESPONSE",path),body));
    }
    private FileRuntime.Authority authority(Command command) {
        return new FileRuntime.Authority() {
            @Override public void check(){repository.guard(command,instance.id());}
            @Override public FileRuntime.Physical physical(){return repository.physical(command);}
            @Override public void created(String id){repository.created(command,instance.id(),id);}
            @Override public void startRequested(){repository.startRequested(command,instance.id());}
            @Override public void started(String time){repository.started(command,instance.id(),time);}
            @Override public void exec(String id){repository.exec(command,instance.id(),id);}
        };
    }
    private void validate(Command command) {
        if(command==null||command.operation()==null||command.operation().id()==null||command.operation().commandId()==null
                ||command.operation().spaceId()==null||command.operation().workspaceId()==null||command.operation().actorId()==null
                ||command.operation().ownerId()==null||command.operation().fence()==null||command.operation().fence()<1
                ||command.operation().generation()<1||command.operation().deadlineAt()==null||command.projectId()<200001
                ||!runtime.policyHash().equals(command.operation().helperPolicyHash()))throw new FileFailure(400,"INVALID_FILE_COMMAND");
        if(captureMode) {
            if(!"CAPTURE".equals(command.operation().kind())||command.upload()!=null)throw new FileFailure(400,"INVALID_FILE_COMMAND");
            return;
        }
        if(command.mutation()!=null) {
            var m=command.mutation();
            boolean restoring="RESTORE_FILE".equals(operationKind(command));
            if(!java.util.Set.of("MOVE","DELETE","DELETE_CLEANUP","RESTORE_FILE","RESTORE_TREE").contains(operationKind(command))||!operationKind(command).equals(m.kind())||(restoring?command.upload()==null:command.upload()!=null)
                    ||!RunnerSignatures.sha256(m.manifestJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(m.manifestHash()))
                throw new FileFailure(400,"INVALID_FILE_COMMAND");
            FileRules.path(m.sourcePath(),false);if("MOVE".equals(m.kind())||restoring||"RESTORE_TREE".equals(m.kind()))FileRules.path(m.targetPath(),false);
            if(restoring){FileRules.sha256(command.upload().sha256());if(command.upload().sizeBytes()<0||command.upload().sizeBytes()>FileRules.MAX_UPLOAD_BYTES)throw new FileFailure(400,"INVALID_FILE_COMMAND");}
            if("DELETE_CLEANUP".equals(m.kind())&&m.parentOperationId()==null)throw new FileFailure(400,"INVALID_FILE_COMMAND");return;
        }
        if("SCAN".equals(command.operation().kind())) {
            if(command.upload()!=null||command.operation().scanEpoch()==null)throw new FileFailure(400,"INVALID_FILE_COMMAND");
            FileRules.path(command.operation().scanParent(),true);return;
        }
        if(command.upload()==null||!"APPLY_UPLOAD".equals(command.operation().kind()))throw new FileFailure(400,"INVALID_FILE_COMMAND");
        FileRules.path(command.upload().relativePath(),false);FileRules.sha256(command.upload().sha256());
        if(command.upload().sizeBytes()<0||command.upload().sizeBytes()>FileRules.MAX_UPLOAD_BYTES)throw new FileFailure(400,"INVALID_FILE_COMMAND");
    }
    private static String operationKind(Command command){return command.operation().kind();}
    private Receipt persistKnown(Command command,FileRuntime.Result result) {
        try {
            repository.recordResult(command,instance.id(),result);completed.remove(command.operation().commandId());var receipt=repository.receipt(command);retire(command,receipt);return receipt;
        } catch(FileFailure failure) {
            if(failure.status()==503)return null; // Preserve the bounded complete result while this instance is alive.
            var unknown=new FileRuntime.Result("UNKNOWN",JSON.writeValueAsString(Map.of("state","UNKNOWN","code","FILE_RESULT_UNKNOWN")),result.stopText());
            repository.recordResult(command,instance.id(),unknown);completed.remove(command.operation().commandId());var receipt=repository.receipt(command);retire(command,receipt);return receipt;
        }
    }
    private void retire(Command command,Receipt receipt) {
        if(receipt==null)return;
        try{runtime.retire(command,JSON.readValue(receipt.stopText(),FileRuntime.Stop.class));}
        catch(RuntimeException pending){/* Durable stop remains authoritative; later GET retries cleanup only. */}
    }
    private FileRuntime.Result executeContent(Command command)throws java.io.IOException {
        var content=assets.open(command.upload().objectKey(),null,null);var id=command.operation().commandId();
        unconfirmedTransports.add(id);Throwable primary=null;
        try {
            if(content.bytes()!=command.upload().sizeBytes()||content.totalBytes()!=command.upload().sizeBytes())throw new FileFailure(503,"ASSET_IDENTITY_MISMATCH");
            var source=new AuthorizedAssetStream(content.stream(),command.upload().sizeBytes(),command.upload().sha256(),()->repository.guard(command,instance.id()));
            return runtime.apply(command,source,authority(command));
        }catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally {
            try{content.close();unconfirmedTransports.remove(id);}
            catch(java.io.IOException|RuntimeException failure){if(primary!=null)primary.addSuppressed(failure);else throw failure;}
        }
    }
    private FileRuntime.Result executeCapture(Command command) {
        var target=repository.captureTarget(command);
        return runtime.capture(command,new FileRuntime.CaptureTarget(target.path(),target.maxBytes()),new FileRuntime.CaptureSink(){
            @Override public void described(long bytes,String hash){repository.describeCapture(command,instance.id(),bytes,hash);}
            @Override public void stopped(FileRuntime.Stop stop){repository.stopCapture(command,instance.id(),stop);}
            @Override public AssetStore.Identity store(long bytes,String hash,java.io.InputStream content){return assets.put(target.objectKey(),bytes,hash,content);}
        },authority(command));
    }
    @Override public SignedResult operate(String token,byte[] body) {
        signatures.authenticate(token);
        if(body==null||body.length>8388608)throw new FileFailure(413,"FILE_COMMAND_TOO_LARGE");
        Command command;FileCaptureData.Command capture=null;
        try{if(captureMode){capture=JSON.readValue(body,FileCaptureData.Command.class);command=capture.file();}else command=JSON.readValue(body,Command.class);}
        catch(RuntimeException invalid){throw new FileFailure(400,"INVALID_FILE_COMMAND");}
        validate(command);signatures.verify(token,binding(command,"POST",path),body);
        if(command.mutation()==null&&body.length>65536)throw new FileFailure(413,"FILE_COMMAND_TOO_LARGE");
        var operation=command.operation();
        if(!command.equals(repository.lookup(operation.spaceId(),operation.workspaceId(),operation.id())))throw new FileFailure(409,"FILE_COMMAND_CONFLICT");
        if(captureMode&&!repository.captureTarget(command).equals(capture.target()))throw new FileFailure(409,"FILE_COMMAND_CONFLICT");
        var previous=repository.receipt(command);
        if(previous!=null){retire(command,previous);return signed(command,previous,path);}
        if(!repository.accept(command,instance,RunnerSignatures.sha256(body)))return signed(command,null,path);
        if(!running.add(operation.commandId()))throw new FileFailure(409,"FILE_COMMAND_ALREADY_RUNNING");
        try {
            repository.guard(command,instance.id());
            if("RESTORE_TREE".equals(operation.kind())) {
                var id=command.operation().commandId();unconfirmedTransports.add(id);
                try(var content=new TreeAssetInputStream(repository.treeFiles(command,instance.id()),assets,AssetStore.treeTransferKey(id),()->repository.guard(command,instance.id()))) {
                    var result=runtime.apply(command,content,authority(command));completed.put(id,result);
                }
                unconfirmedTransports.remove(id);
                return signed(command,persistKnown(command,completed.get(id)),path);
            }
            if(captureMode) {
                var result=executeCapture(command);completed.put(operation.commandId(),result);
                return signed(command,persistKnown(command,result),path);
            }
            if("SCAN".equals(operation.kind())||command.mutation()!=null&&!"RESTORE_FILE".equals(operation.kind())) {
                var result=runtime.apply(command,new java.io.ByteArrayInputStream(new byte[0]),authority(command));completed.put(operation.commandId(),result);
                return signed(command,persistKnown(command,result),path);
            }
            var result=executeContent(command);completed.put(operation.commandId(),result);
            return signed(command,persistKnown(command,result),path);
        } catch(RuntimeException|java.io.IOException failure) {
            var result=completed.get(operation.commandId());
            if(result==null&&!unconfirmedTransports.contains(operation.commandId())&&repository.mayObserve(command,instance.id())) {
                result=runtime.reconcile(command,authority(command),false);
                if(result!=null)completed.put(operation.commandId(),result);
            }
            return signed(command,result==null?null:persistKnown(command,result),path);
        } finally {running.remove(operation.commandId());}
    }
    @Override public SignedResult query(String token,String operationId) {
        String path=this.path+"/"+operationId;var binding=signatures.verifiedQuery(token,path);
        if(!binding.operationId().equals(FileRules.id(operationId)))throw new WorkspaceFailure(401,"RUNNER_COMMAND_BINDING_REJECTED");
        var command=repository.lookup(binding.spaceId(),binding.workspaceId(),binding.operationId());
        if(captureMode!= "CAPTURE".equals(command.operation().kind()))throw new FileFailure(400,"INVALID_FILE_COMMAND");
        if(!binding.equals(binding(command,"GET",path)))throw new WorkspaceFailure(401,"RUNNER_COMMAND_BINDING_REJECTED");
        var previous=repository.receipt(command);
        if(previous!=null){completed.remove(command.operation().commandId());retire(command,previous);return signed(command,previous,path);}
        if(running.contains(command.operation().commandId()))return signed(command,null,path);
        if(unconfirmedTransports.contains(command.operation().commandId()))return signed(command,null,path);
        var result=completed.get(command.operation().commandId());
        if(result!=null)return signed(command,persistKnown(command,result),path);
        var neverAccepted=repository.observeNeverAccepted(command,instance.id(),
                RunnerSignatures.sha256(JSON.writeValueAsBytes(captureMode?new FileCaptureData.Command(FileCaptureData.PROTOCOL,command,repository.captureTarget(command)):command)));
        if(neverAccepted!=null)return signed(command,neverAccepted,path);
        if(!repository.mayObserve(command,instance.id()))return signed(command,null,path);
        result=runtime.reconcile(command,authority(command),repository.priorRunnerStopped(command,instance.id()));
        if(result!=null){completed.put(command.operation().commandId(),result);return signed(command,persistKnown(command,result),path);}
        return signed(command,null,path);
    }
}
