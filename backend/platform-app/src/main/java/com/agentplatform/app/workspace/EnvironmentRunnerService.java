package com.agentplatform.app.workspace;

import com.agentplatform.api.workspace.EnvironmentRunnerApi;
import com.agentplatform.api.workspace.RunnerControlApi.SignedResult;
import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.EnvironmentExecutionData.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** Small signed control receipts. File/exec payloads never become public management responses. */
public final class EnvironmentRunnerService implements EnvironmentRunnerApi {
    public static final String PATH="/internal/operations/v2/environments";
    public static final String PATH_V3="/internal/operations/v3/environments";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final EnvironmentExecutionRepository repository;
    private final RunnerSignatures signatures;
    private final SandboxRuntime runtime;
    private final String image,policy;
    private final RunnerInstance instance;
    private final WorkspaceEffectBoundary boundary;
    public EnvironmentRunnerService(EnvironmentExecutionRepository repository,RunnerSignatures signatures,SandboxRuntime runtime,String image,String policy,RunnerInstance instance) {
        this(repository,signatures,runtime,image,policy,instance,WorkspaceEffectBoundary.NONE);
    }
    public EnvironmentRunnerService(EnvironmentExecutionRepository repository,RunnerSignatures signatures,SandboxRuntime runtime,String image,String policy,RunnerInstance instance,WorkspaceEffectBoundary boundary) {
        this.repository=repository;this.signatures=signatures;this.runtime=runtime;this.image=image;this.policy=policy;this.instance=instance;this.boundary=boundary;
        repository.registerRunner(instance);
    }
    public static RunnerSignatures.Binding binding(Command command,String method,String path) {
        return new RunnerSignatures.Binding(method,path,command.commandId(),command.operationId(),command.spaceId(),command.workspaceId(),command.generation(),command.fence());
    }
    private void validate(Command command,boolean v3) {
        if(command==null||!(v3?"ENVIRONMENT_V3":"ENVIRONMENT_V2").equals(command.protocol())
                ||command.kind()==null
                ||!(v3?Set.of("PREPARE","WAKE","STOP","SLEEP","DELETE"):Set.of("PREPARE","STOP")).contains(command.kind())
                ||!("USER".equals(command.initiator())||"SYSTEM".equals(command.initiator()))||command.commandId()==null||command.operationId()==null
                ||command.spaceId()==null||command.workspaceId()==null||command.environmentId()==null||command.ownerId()==null
                ||command.creationCommandId()==null||command.generation()<1||command.fence()<1||command.creationFence()<1
                ||command.creationFence()>command.fence()||command.projectId()<200001||command.deadline()==null
                ||!image.equals(command.imageId())||!policy.equals(command.policyHash())
                ||Set.of("PREPARE","WAKE").contains(command.kind())&&(!"USER".equals(command.initiator())||command.actorId()==null)
                ||Set.of("SLEEP","DELETE").contains(command.kind())&&(!"USER".equals(command.initiator())||command.actorId()==null||command.containerId()==null||command.containerStartedAt()==null)
                ||"STOP".equals(command.kind())&&(!"SYSTEM".equals(command.initiator())||command.containerId()==null||command.containerStartedAt()==null))
            throw new WorkspaceFailure(400,"INVALID_ENVIRONMENT_COMMAND");
    }
    private SignedResult signed(Command command,Receipt receipt,String path) {
        byte[] body=JSON.writeValueAsBytes(receipt);
        if(body.length>16384)throw new WorkspaceFailure(503,"RUNNER_RECEIPT_TOO_LARGE");
        return new SignedResult(body,signatures.sign(binding(command,"RESPONSE",path),body));
    }
    private static Receipt result(Command command,String state,String effect,String error,SandboxRuntime.Container container,SandboxRuntime.Stop stop) {
        return new Receipt(command.commandId(),command.operationId(),command.environmentId(),command.workspaceId(),command.generation(),command.fence(),state,effect,error,container,stop,1);
    }
    private void cleanup(Command command,Receipt receipt) {
        if(receipt.stop()==null||!Set.of("COMMITTED","FAILED").contains(receipt.state()))return;
        try {runtime.removeStopped(command.physicalIdentity(),receipt.stop());}
        catch(RuntimeException retained) {
            System.getLogger("platform.workspace.environment").log(System.Logger.Level.WARNING,"Durable stop receipt retained; exact container cleanup pending");
        }
    }
    private Receipt finishCleanup(Command command,Receipt receipt) {
        if(receipt.stop()==null||!Set.of("COMMITTED","FAILED").contains(receipt.state()))return receipt;
        if(receipt.revision()==2)return receipt;
        try {
            repository.checkRunner(command,instance.id());
            var removal=runtime.confirmRemoval(command.physicalIdentity(),receipt.stop());
            return repository.confirmCleanup(command,removal,instance.id());
        } catch(RuntimeException pending) {
            return new Receipt(command.commandId(),command.operationId(),command.environmentId(),command.workspaceId(),
                    command.generation(),command.fence(),"RUNNING","MAY_HAVE_STARTED","ENVIRONMENT_CLEANUP_PENDING",null,null,0);
        }
    }
    @Override public SignedResult operate(String token,byte[] body) {return operate(token,body,false);}
    @Override public SignedResult operateV3(String token,byte[] body) {return operate(token,body,true);}
    private SignedResult operate(String token,byte[] body,boolean v3) {
        String path=v3?PATH_V3:PATH;
        signatures.authenticate(token);
        if(body==null||body.length>16384)throw new WorkspaceFailure(413,"RUNNER_BODY_TOO_LARGE");
        Command command;
        try {command=JSON.readValue(body,Command.class);}catch(RuntimeException invalid) {throw new WorkspaceFailure(400,"INVALID_ENVIRONMENT_COMMAND");}
        validate(command,v3);signatures.verify(token,binding(command,"POST",path),body);
        if(!command.equals(repository.lookup(command.operationId())))throw new WorkspaceFailure(409,"RUNNER_COMMAND_CONFLICT");
        var previous=repository.accept(command,RunnerSignatures.sha256(body),instance.id());
        if(previous!=null) {
            if(v3)return signed(command,finishCleanup(command,previous),path);
            cleanup(command,previous);return signed(command,previous,path);
        }
        boundary.await("environment-runner-accepted");
        Receipt receipt;
        try {
            repository.check(command);
            repository.checkRunner(command,instance.id());
            if(Set.of("PREPARE","WAKE").contains(command.kind())) {
                var ready=runtime.prepare(command.physicalIdentity(),()->repository.checkRunner(command,instance.id()));
                receipt=result(command,"COMMITTED","READY",null,ready,null);
            } else {
                var prior=v3?repository.priorStop(command):null;
                var stopped=prior==null?runtime.stop(command.physicalIdentity()):prior;
                receipt=result(command,"COMMITTED","STOPPED",null,null,stopped);
            }
        } catch(SandboxRuntime.RejectedBeforeEffect rejected) {
            if(!Set.of("PREPARE","WAKE").contains(command.kind()))return signed(command,new Receipt(command.commandId(),command.operationId(),command.environmentId(),command.workspaceId(),command.generation(),command.fence(),
                    "RUNNING","MAY_HAVE_STARTED","SANDBOX_STOP_UNCONFIRMED",null,null,0),path);
            receipt=result(command,"FAILED","NOT_STARTED",rejected.getMessage(),null,null);
        } catch(RuntimeException unknown) {
            if(unknown instanceof WorkspaceFailure failure && Set.of("ENVIRONMENT_AUTHORIZATION_REVOKED","ENVIRONMENT_STALE_OWNER","ENVIRONMENT_STORAGE_UNAVAILABLE","RUNNER_INSTANCE_STOP_UNPROVEN").contains(failure.code())) {
                try {runtime.stop(command.physicalIdentity());}
                catch(RuntimeException unconfirmed) { /* Retain physical identity and capacity for host-proof convergence. */ }
            }
            // A failed management response is not a terminal physical fact. Preserve
            // the original accepted command for query-only reconciliation; never POST again.
            return signed(command,new Receipt(command.commandId(),command.operationId(),command.environmentId(),command.workspaceId(),command.generation(),command.fence(),
                    "RUNNING","MAY_HAVE_STARTED","SANDBOX_RESULT_UNKNOWN",null,null,0),path);
        }
        boundary.await("environment-readiness-before-receipt");
        repository.commitReceipt(command,receipt,instance.id()); // Stop fact must commit before cleanup.
        boundary.await("environment-receipt-before-response");
        if(v3)return signed(command,finishCleanup(command,receipt),path);
        cleanup(command,receipt);return signed(command,receipt,path);
    }
    @Override public SignedResult query(String token,String operationId) {return query(token,operationId,false);}
    @Override public SignedResult queryV3(String token,String operationId) {return query(token,operationId,true);}
    private SignedResult query(String token,String operationId,boolean v3) {
        String path=(v3?PATH_V3:PATH)+"/"+operationId;
        var request=signatures.verifiedQuery(token,path);
        var command=repository.lookup(request.operationId());validate(command,v3);
        signatures.verify(token,binding(command,"GET",path),new byte[0]);
        var receipt=repository.receipt(command);
        if("RUNNING".equals(receipt.state())) {
            repository.checkRunner(command,instance.id());
            Receipt observed=null;
            try {
                if(Set.of("PREPARE","WAKE").contains(command.kind())) observed=result(command,"COMMITTED","READY",null,
                        runtime.verifyReady(command.physicalIdentity(),()->repository.checkRunner(command,instance.id())),null);
                else if(!runtime.inspect(command.physicalIdentity()).running()) observed=result(command,"COMMITTED","STOPPED",null,null,runtime.stop(command.physicalIdentity()));
            } catch(RuntimeException notProven) { /* Query never creates, starts, replays or frees resources. */ }
            if(observed!=null) {repository.commitReceipt(command,observed,instance.id());receipt=observed;}
        }
        if(v3)return signed(command,finishCleanup(command,receipt),path);
        cleanup(command,receipt);return signed(command,receipt,path);
    }
}
