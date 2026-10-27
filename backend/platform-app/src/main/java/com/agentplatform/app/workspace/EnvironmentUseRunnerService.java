package com.agentplatform.app.workspace;

import com.agentplatform.api.workspace.EnvironmentUseRunnerApi;
import com.agentplatform.api.workspace.RunnerControlApi.SignedResult;
import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.EnvironmentUseData.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.json.JsonMapper;

/** One accepted use is executed once; queries can confirm termination but cannot reconstruct or replay work. */
public final class EnvironmentUseRunnerService implements EnvironmentUseRunnerApi {
    public static final String PATH="/internal/workspace-uses/v1";
    private final JsonMapper json;
    private final String path;
    private final boolean shell;
    private final EnvironmentUseRepository repository;
    private final RunnerSignatures signatures;
    private final SandboxRuntime runtime;
    private final RunnerInstance instance;
    private final WorkspaceEffectBoundary boundary;
    private final FileHelperRepository fileHelpers;
    // The database admits one use. The local barrier additionally prevents a lost
    // HTTP response/query from stopping an original invocation that is still exiting.
    private final AtomicReference<UUID> inFlight=new AtomicReference<>();
    public EnvironmentUseRunnerService(EnvironmentUseRepository repository,RunnerSignatures signatures,SandboxRuntime runtime,RunnerInstance instance) {
        this(repository,signatures,runtime,instance,WorkspaceEffectBoundary.NONE);
    }
    public EnvironmentUseRunnerService(EnvironmentUseRepository repository,RunnerSignatures signatures,SandboxRuntime runtime,RunnerInstance instance,WorkspaceEffectBoundary boundary) {
        this(repository,signatures,runtime,instance,boundary,null);
    }
    public EnvironmentUseRunnerService(EnvironmentUseRepository repository,RunnerSignatures signatures,SandboxRuntime runtime,RunnerInstance instance,
            WorkspaceEffectBoundary boundary,FileHelperRepository fileHelpers) {
        this(repository,signatures,runtime,instance,boundary,fileHelpers,false);
    }
    EnvironmentUseRunnerService(EnvironmentUseRepository repository,RunnerSignatures signatures,SandboxRuntime runtime,RunnerInstance instance,
            WorkspaceEffectBoundary boundary,FileHelperRepository fileHelpers,boolean shell) {
        this.repository=repository;this.signatures=signatures;this.runtime=runtime;this.instance=instance;this.boundary=boundary;this.fileHelpers=fileHelpers;
        this.shell=shell;this.path=shell?WorkspaceShellRunnerService.PATH:PATH;
        this.json=shell?com.agentplatform.infrastructure.workspace.EnvironmentUseCodecs.shell():com.agentplatform.infrastructure.workspace.EnvironmentUseCodecs.legacy();
    }
    public static RunnerSignatures.Binding binding(Command command,String method,String path) {
        return new RunnerSignatures.Binding(method,path,command.id(),command.id(),command.spaceId(),command.workspaceId(),command.generation(),command.fence());
    }
    private SignedResult signed(Command command,String path) {
        byte[] body=json.writeValueAsBytes(repository.receipt(command));
        return new SignedResult(body,signatures.sign(binding(command,"RESPONSE",path),body));
    }
    private void validate(Command command) {
        if(command==null||command.id()==null||command.spaceId()==null||command.workspaceId()==null||command.environmentId()==null
                ||command.actorId()==null||command.ownerId()==null||command.request()==null||command.executeUntil()==null||command.deadline()==null
                ||command.creationCommandId()==null||command.generation()<1||command.fence()<1||command.creationFence()<1||command.projectId()<200001
                ||(shell?!(command.request() instanceof WorkspaceShellData.Request):!(command.request() instanceof Request))
                ||!command.request().kind().equals(command.kind()))throw new WorkspaceFailure(400,"INVALID_WORKSPACE_USE");
    }
    private Fact unknown(Command command,SandboxRuntime.Stop stop) {
        return new Fact(command.id(),command.environmentId(),command.generation(),command.fence(),command.requestHash(),instance.id(),
                "UNKNOWN",null,stop,"ENVIRONMENT_USE_INTERRUPTED");
    }
    private void cleanup(Command command) {
        if(command.request().file()!=null&&fileHelpers!=null) {
            var stopped=fileHelpers.retirement(command);
            if(stopped!=null) {
                try {runtime.removeStoppedFileHelper(command.physicalIdentity(),command.id(),runtime.fileHelperIntent(command.physicalIdentity(),command.id()),stopped);}
                catch(RuntimeException pending) {System.getLogger("platform.workspace.environment").log(System.Logger.Level.WARNING,"Durable file-helper stop retained; exact container cleanup pending");}
            }
        }
        if(!repository.stopCommitted(command.id()))return;
        var fact=repository.committedFact(command);
        if(fact.stop()!=null) {
            try {runtime.removeStopped(command.physicalIdentity(),fact.stop());}
            catch(RuntimeException pending) {System.getLogger("platform.workspace.environment").log(System.Logger.Level.WARNING,"Durable data-use stop retained; exact container cleanup pending");}
        }
    }
    private SandboxRuntime.FileHelperAuthority fileAuthority(Command command) {
        if(fileHelpers==null)throw new WorkspaceFailure(503,"FILE_HELPER_UNAVAILABLE");
        var intent=runtime.fileHelperIntent(command.physicalIdentity(),command.id());
        return new SandboxRuntime.FileHelperAuthority() {
            @Override public FileHelperData.Intent intent() {return intent;}
            @Override public FileHelperData.Snapshot snapshot() {return fileHelpers.find(command,instance.id());}
            @Override public void created(FileHelperData.Physical physical) {repository.check(command,instance.id());fileHelpers.created(command,instance.id(),physical);}
            @Override public void requestStart() {repository.check(command,instance.id());fileHelpers.requestStart(command,instance.id());}
            @Override public void started(FileHelperData.Physical physical) {repository.check(command,instance.id());fileHelpers.started(command,instance.id(),physical);}
            @Override public void recordExec(FileHelperData.Physical physical) {repository.check(command,instance.id());fileHelpers.recordExec(command,instance.id(),physical);}
            @Override public void stopped(FileHelperData.Stopped stop) {fileHelpers.stopped(command,instance.id(),stop);}
            @Override public FileHelperData.Stopped retirement() {return fileHelpers.retirement(command);}
            @Override public void check() {repository.check(command,instance.id());}
        };
    }
    @Override public SignedResult operate(String token,byte[] body) {
        signatures.authenticate(token);
        if(body==null||body.length>RunnerSignatures.bodyLimit("POST",path))throw new WorkspaceFailure(413,"RUNNER_BODY_TOO_LARGE");
        Command command;
        try {command=json.readValue(body,Command.class);}catch(RuntimeException invalid) {throw new WorkspaceFailure(400,"INVALID_WORKSPACE_USE");}
        validate(command);signatures.verify(token,binding(command,"POST",path),body);
        if(!command.equals(repository.lookup(command.id())))throw new WorkspaceFailure(409,"RUNNER_COMMAND_CONFLICT");
        if(!inFlight.compareAndSet(null,command.id())) {
            if(!command.id().equals(inFlight.get()))throw new WorkspaceFailure(409,"ENVIRONMENT_USE_BUSY");
            return signed(command,path);
        }
        try {
            if(!repository.accept(command,instance.id()))return signed(command,path);
            var authority=new SandboxRuntime.UseAuthority() {
                @Override public void check() {repository.check(command,instance.id());}
                @Override public void recordExec(String container,String exec) {repository.recordExec(command,instance.id(),container,exec);}
            };
            Fact fact;
            try {
                authority.check();
                var cancelled=(java.util.function.BooleanSupplier)()->!Instant.now().isBefore(command.executeUntil())||repository.cancelled(command);
                SandboxRuntime.Output output;
                if(command.request().file()!=null) {
                    var fileAuthority=fileAuthority(command);
                    fileHelpers.intent(command,instance.id(),fileAuthority.intent());
                    output=runtime.file(command.physicalIdentity(),command.id(),command.request().file(),fileAuthority,cancelled);
                } else if(shell) output=runtime.shell(command.physicalIdentity(),command.id(),command.request().shell(),authority,cancelled);
                else output=runtime.probe(command.physicalIdentity(),command.id(),command.request().probe(),authority,cancelled);
                fact=output.timedOut()||output.cancelled()?unknown(command,output.stopReceipt()):
                        new Fact(command.id(),command.environmentId(),command.generation(),command.fence(),command.requestHash(),instance.id(),"KNOWN",output,output.stopReceipt(),null);
            } catch(SandboxRuntime.Terminated terminated) {fact=unknown(command,terminated.receipt());}
            catch(RuntimeException uncertain) {
                // A lost transport/authorization never grants a replay. Stop is precise,
                // and storage/owner failure may prevent recording it until later query.
                SandboxRuntime.Stop stop=null;
                try {stop=runtime.stop(command.physicalIdentity());}catch(RuntimeException unconfirmed) { /* Keep slot. */ }
                if(stop==null)return signed(command,path);
                fact=unknown(command,stop);
            }
            boundary.await("use-output-before-result");
            try {repository.commitFact(command,instance.id(),fact);}
            catch(RuntimeException lostBeforeCommit) {
                // Cancellation/revocation can win after exec exits but before the
                // ordinary output commits. Converge only through actual termination.
                var stop=runtime.stop(command.physicalIdentity());
                repository.commitFact(command,instance.id(),unknown(command,stop));
            }
            boundary.await("use-result-before-response");
            return signed(command,path);
        } finally {inFlight.compareAndSet(command.id(),null);}
    }
    @Override public SignedResult query(String token,String id) {
        String path=this.path+"/"+id;
        var binding=signatures.verifiedQuery(token,path); // Authenticate all bindings before database access.
        var command=repository.lookup(binding.operationId());validate(command);
        signatures.verify(token,binding(command,"GET",path),new byte[0]);
        var receipt=repository.receipt(command);
        if(receipt.resultHash()==null&&!command.id().equals(inFlight.get())) {
            repository.checkStopObservation(command,instance.id());
            try {
                if(command.request().file()!=null)runtime.stopFileHelper(command.physicalIdentity(),command.id(),fileAuthority(command));
                var stop=runtime.stop(command.physicalIdentity());
                repository.commitFact(command,instance.id(),unknown(command,stop));
            } catch(RuntimeException unproven) { /* No result, no replay and no slot release. */ }
        }
        cleanup(command);return signed(command,path);
    }
}
