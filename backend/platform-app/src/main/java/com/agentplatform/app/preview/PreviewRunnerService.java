package com.agentplatform.app.preview;

import com.agentplatform.api.files.PreviewRunnerApi;
import com.agentplatform.api.workspace.RunnerControlApi.SignedResult;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.domain.preview.*;
import com.agentplatform.domain.preview.PreviewData.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import com.agentplatform.infrastructure.workspace.SignedPreviewRunnerClient;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.json.JsonMapper;

/** Durable handoff starts once. HTTP requests return promptly while conversion is independently owned. */
public final class PreviewRunnerService implements PreviewRunnerApi,AutoCloseable {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final PreviewRepository repository;
    private final PreviewRuntime runtime;
    private final AssetStore assets;
    private final RunnerSignatures signatures;
    private final Path root;
    private final UUID runner;
    private final Set<UUID> running=ConcurrentHashMap.newKeySet();
    private final Set<UUID> sourceUnclosed=ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<UUID,PreviewRuntime.Result> completed=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),
            Thread.ofPlatform().name("preview-physical").factory(),new ThreadPoolExecutor.AbortPolicy());

    public PreviewRunnerService(PreviewRepository repository,PreviewRuntime runtime,AssetStore assets,RunnerSignatures signatures,Path root) {
        this.repository=repository;this.runtime=runtime;this.assets=assets;this.signatures=signatures;this.runner=runtime.runnerInstance().id();
        this.root=root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
            if(Files.isSymbolicLink(this.root)||!this.root.toRealPath().equals(this.root))throw new IOException("Noncanonical staging root");
            if(Files.getFileStore(this.root).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(this.root,PosixFilePermissions.fromString("rwx------"));
        } catch(IOException invalid){throw new IllegalArgumentException("Protected preview staging root required",invalid);}
    }
    private Path directory(Command c)throws IOException {
        Path path=root.resolve(c.commandId().toString());
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(path);
        if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path)||!path.toRealPath().getParent().equals(root))throw new IOException("Invalid preview staging directory");
        if(Files.getFileStore(path).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));
        return path;
    }
    private void guard(Command c){repository.runnerGuard(c,runner);}
    private PreviewRuntime.Guard authority(Command c){return new PreviewRuntime.Guard(){
        @Override public void check(){guard(c);}
        @Override public PreviewRuntime.Physical physical(){return repository.runnerPhysical(c,runner);}
        @Override public boolean startWasRequested(){return repository.runnerStartWasRequested(c,runner);}
        @Override public void observed(PreviewRuntime.Physical physical,boolean startRequested){
            repository.runnerObservedPhysical(c,runner,physical.containerId(),physical.startedAt(),startRequested);
        }
        @Override public void execCreated(String id){repository.runnerExecCreated(c,runner,id);}
        @Override public void created(String id){repository.runnerCreated(c,runner,id);}
        @Override public void startRequested(){repository.runnerStartRequested(c,runner);}
        @Override public void started(String at){repository.runnerStarted(c,runner,at);}
    };}
    private SignedResult response(Command c,Receipt receipt,String path){
        byte[] bytes=JSON.writeValueAsBytes(receipt);
        if(bytes.length>65536)throw new FileFailure(503,"PREVIEW_RECEIPT_TOO_LARGE");
        return new SignedResult(bytes,signatures.sign(SignedPreviewRunnerClient.binding(c,"RESPONSE",path),bytes));
    }
    private void validate(Command c){
        if(c==null||c.commandId()==null||c.previewId()==null||c.spaceId()==null||c.workspaceId()==null||c.actorId()==null||c.versionId()==null
                ||c.fileId()==null||c.ownerId()==null||c.generation()<1||c.fence()<1||c.deadlineAt()==null
                ||c.sourceBytes()<0||c.sourceBytes()>PreviewData.MAX_BYTES||c.sourceSha256()==null||!c.sourceSha256().matches("[a-f0-9]{64}")
                ||!Objects.equals(runtime.policyHash(),c.policyHash()))throw new FileFailure(400,"INVALID_PREVIEW_COMMAND");
    }
    @Override public SignedResult operate(String token,byte[] body){
        signatures.authenticate(token);
        if(body==null||body.length>65536)throw new FileFailure(413,"PREVIEW_COMMAND_TOO_LARGE");
        Command c;
        try{c=JSON.readValue(body,Command.class);}catch(RuntimeException invalid){throw new FileFailure(400,"INVALID_PREVIEW_COMMAND");}
        validate(c);signatures.verify(token,SignedPreviewRunnerClient.binding(c,"POST",SignedPreviewRunnerClient.PATH),body);
        if(!c.equals(repository.runnerLookup(c.previewId())))throw new FileFailure(409,"PREVIEW_COMMAND_CONFLICT");
        var receipt=repository.receipt(c);
        if(receipt!=null){retire(c,receipt);return response(c,receipt,SignedPreviewRunnerClient.PATH);}
        if(repository.runnerClaim(c,runner)){
            running.add(c.commandId());
            try{executor.execute(()->execute(c));}
            catch(RejectedExecutionException stopping){running.remove(c.commandId());/* GET may prove this never-started command; no second POST. */}
        }
        return response(c,null,SignedPreviewRunnerClient.PATH);
    }
    private Path stage(Command c,Path dir)throws IOException {
        guard(c);
        if(Files.getFileStore(dir).getUsableSpace()<5L*1024*1024*1024+2*PreviewData.MAX_BYTES)throw new FileFailure(503,"PREVIEW_DISK_ADMISSION");
        Path input=dir.resolve("input");
        if(Files.exists(input,LinkOption.NOFOLLOW_LINKS))throw new FileFailure(409,"PREVIEW_SOURCE_ALREADY_STAGED");
        MessageDigest digest;
        try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        var source=assets.open(c.sourceObjectKey(),null,null);sourceUnclosed.add(c.commandId());
        try {
            if(source.bytes()!=c.sourceBytes()||source.totalBytes()!=c.sourceBytes())throw new IOException("Source length mismatch");
            try(var output=Files.newOutputStream(input,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
                byte[] chunk=new byte[65536];long count=0,checked=0;
                for(int read;(read=source.stream().read(chunk))!=-1;){
                    count+=read;if(count>c.sourceBytes())throw new IOException("Source length overflow");
                    if(count-checked>=1024*1024){guard(c);checked=count;}
                    digest.update(chunk,0,read);output.write(chunk,0,read);
                }
                if(count!=c.sourceBytes()||!HexFormat.of().formatHex(digest.digest()).equals(c.sourceSha256()))throw new IOException("Source integrity mismatch");
            }
            if(Files.getFileStore(input).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(input,PosixFilePermissions.fromString("r--------"));
        } finally {
            source.close(); // Failed close retains the transport permit and blocks publication.
            sourceUnclosed.remove(c.commandId());
            repository.recordSourceTransferClosed(c,runner);
        }
        guard(c);return input;
    }
    private Receipt persist(Command c,PreviewRuntime.Result result)throws IOException {
        if(result==null||result.stop()==null||!result.stop().confirmed())return null;
        String key=null;
        if("READY".equals(result.state())){
            try{guard(c);}
            catch(FileFailure denied){
                if(denied.status()!=401&&denied.status()!=403&&denied.status()!=409)throw denied;
                var stopped=new Receipt(c.commandId(),c.previewId(),c.generation(),c.fence(),runner,"FAILED",null,null,
                        0,null,null,null,false,"PREVIEW_AUTHORIZATION_REVOKED",false,result.stop());
                repository.runnerReceipt(c,runner,stopped);completed.remove(c.commandId());retire(c,stopped);return stopped;
            }
            if(result.sourceReferenced())key=c.sourceObjectKey();
            else {
                Path output=result.output();
                if(output==null||Files.isSymbolicLink(output)||!Files.isRegularFile(output,LinkOption.NOFOLLOW_LINKS)
                        ||!output.toRealPath().startsWith(root.resolve(c.commandId().toString()))
                        ||Files.size(output)!=result.bytes()||result.bytes()>PreviewData.MAX_BYTES)throw new IOException("Invalid preview output");
                try(var stream=Files.newInputStream(output,LinkOption.NOFOLLOW_LINKS)){
                    var stored=assets.put(c.outputObjectKey(),result.bytes(),result.sha256(),stream);
                    if(stored.bytes()!=result.bytes()||!stored.sha256().equals(result.sha256()))throw new IOException("Stored preview mismatch");
                    key=stored.key();
                }
            }
        }
        var receipt=new Receipt(c.commandId(),c.previewId(),c.generation(),c.fence(),runner,result.state(),result.format(),result.mediaType(),
                result.bytes(),result.sha256(),result.pages(),key,result.sourceReferenced(),result.errorCode(),result.retryable(),result.stop());
        repository.runnerReceipt(c,runner,receipt);completed.remove(c.commandId());retire(c,receipt);return receipt;
    }
    private void remember(Command c,PreviewRuntime.Result result){
        if(result!=null&&result.stop()!=null&&result.stop().confirmed())completed.put(c.commandId(),result);
        else completed.remove(c.commandId());
    }
    private void execute(Command c){
        try {
            var dir=directory(c);var input=stage(c,dir);var result=runtime.render(c,input,dir.resolve("output"),authority(c));
            remember(c,result);persist(c,result);
        } catch(RuntimeException|IOException failure){
            // No response body or file contents enter logs. Same command is reconciled below/through GET.
            if(!sourceUnclosed.contains(c.commandId()))try {
                var result=completed.get(c.commandId());
                if(result==null)result=runtime.reconcile(c,directory(c).resolve("output"),authority(c));
                if(result!=null){remember(c,result);persist(c,result);}
            }catch(RuntimeException|IOException pending){/* Keep exact identity and capacity for query. */}
        } finally{running.remove(c.commandId());}
    }
    @Override public SignedResult query(String token,String id){
        String path=SignedPreviewRunnerClient.PATH+"/"+id;var binding=signatures.verifiedQuery(token,path);
        UUID preview;
        try{preview=UUID.fromString(id);}catch(IllegalArgumentException invalid){throw new FileFailure(400,"INVALID_PREVIEW_ID");}
        var c=repository.runnerLookup(preview);
        if(!binding.equals(SignedPreviewRunnerClient.binding(c,"GET",path)))throw new FileFailure(401,"PREVIEW_BINDING_REJECTED");
        Receipt receipt=repository.receipt(c);
        if(receipt==null&&!running.contains(c.commandId())&&!sourceUnclosed.contains(c.commandId())){
            try {
                if(!repository.runnerObserveNeverAccepted(c,runner))return response(c,null,path);
                var result=completed.get(c.commandId());
                if(result==null)result=runtime.reconcile(c,directory(c).resolve("output"),authority(c));
                if(result!=null){remember(c,result);receipt=persist(c,result);}
            }catch(RuntimeException|IOException pending){/* Query never starts another conversion. */}
        }
        if(receipt!=null)retire(c,receipt);
        return response(c,receipt,path);
    }
    private void retire(Command c,Receipt receipt){
        if(receipt.stop()==null||!receipt.stop().confirmed())return;
        try {
            runtime.retire(c,receipt.stop());
            Path dir=root.resolve(c.commandId().toString());
            if(Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(dir)){
                Path output=dir.resolve("output");
                if(Files.isDirectory(output,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(output)){
                    try(var files=Files.list(output)){
                        for(Path file:files.toList())if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(file))Files.delete(file);
                    }
                    Files.delete(output);
                }
                try(var files=Files.list(dir)){
                    for(Path file:files.toList()){
                        if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(file))Files.delete(file);
                    }
                }
                Files.deleteIfExists(dir);
            }
        }catch(RuntimeException|IOException cleanupPending){/* Immutable receipt retained; later GET retries cleanup only. */}
    }
    @Override public void close(){executor.shutdownNow();try{executor.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
}
