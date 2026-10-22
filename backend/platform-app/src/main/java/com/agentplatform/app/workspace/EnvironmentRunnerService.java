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
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final EnvironmentExecutionRepository repository;
    private final RunnerSignatures signatures;
    private final SandboxRuntime runtime;
    private final String image,policy;
    private final RunnerInstance instance;
    public EnvironmentRunnerService(EnvironmentExecutionRepository repository,RunnerSignatures signatures,SandboxRuntime runtime,String image,String policy,RunnerInstance instance) {
        this.repository=repository;this.signatures=signatures;this.runtime=runtime;this.image=image;this.policy=policy;this.instance=instance;
        repository.registerRunner(instance);
    }
    public static RunnerSignatures.Binding binding(Command command,String method,String path) {
        return new RunnerSignatures.Binding(method,path,command.commandId(),command.operationId(),command.spaceId(),command.workspaceId(),command.generation(),command.fence());
    }
    private void validate(Command command) {
        if(command==null||!"ENVIRONMENT_V2".equals(command.protocol())||!("PREPARE".equals(command.kind())||"STOP".equals(command.kind()))
                ||!("USER".equals(command.initiator())||"SYSTEM".equals(command.initiator()))||command.commandId()==null||command.operationId()==null
                ||command.spaceId()==null||command.workspaceId()==null||command.environmentId()==null||command.ownerId()==null
                ||command.creationCommandId()==null||command.generation()<1||command.fence()<1||command.creationFence()<1
                ||command.creationFence()>command.fence()||command.projectId()<200001||command.deadline()==null
                ||!image.equals(command.imageId())||!policy.equals(command.policyHash())
                ||"PREPARE".equals(command.kind())&&(!"USER".equals(command.initiator())||command.actorId()==null)
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
    @Override public SignedResult operate(String token,byte[] body) {
        signatures.authenticate(token);
        if(body==null||body.length>16384)throw new WorkspaceFailure(413,"RUNNER_BODY_TOO_LARGE");
        Command command;
        try {command=JSON.readValue(body,Command.class);}catch(RuntimeException invalid) {throw new WorkspaceFailure(400,"INVALID_ENVIRONMENT_COMMAND");}
        validate(command);signatures.verify(token,binding(command,"POST",PATH),body);
        if(!command.equals(repository.lookup(command.operationId())))throw new WorkspaceFailure(409,"RUNNER_COMMAND_CONFLICT");
        var previous=repository.accept(command,RunnerSignatures.sha256(body),instance.id());
        if(previous!=null) {cleanup(command,previous);return signed(command,previous,PATH);}
        Receipt receipt;
        try {
            repository.check(command);
            repository.checkRunner(command,instance.id());
            if("PREPARE".equals(command.kind())) {
                var ready=runtime.prepare(command.physicalIdentity(),()->repository.check(command));
                receipt=result(command,"COMMITTED","READY",null,ready,null);
            } else {
                var stopped=runtime.stop(command.physicalIdentity());
                receipt=result(command,"COMMITTED","STOPPED",null,null,stopped);
            }
        } catch(SandboxRuntime.RejectedBeforeEffect rejected) {
            receipt=result(command,"FAILED","NOT_STARTED",rejected.getMessage(),null,null);
        } catch(RuntimeException unknown) {
            if(unknown instanceof WorkspaceFailure failure && Set.of("ENVIRONMENT_AUTHORIZATION_REVOKED","ENVIRONMENT_STALE_OWNER","ENVIRONMENT_STORAGE_UNAVAILABLE").contains(failure.code())) {
                try {runtime.stop(command.physicalIdentity());}
                catch(RuntimeException unconfirmed) { /* Retain physical identity and capacity for host-proof convergence. */ }
            }
            // A failed management response is not a terminal physical fact. Preserve
            // the original accepted command for query-only reconciliation; never POST again.
            return signed(command,new Receipt(command.commandId(),command.operationId(),command.environmentId(),command.workspaceId(),command.generation(),command.fence(),
                    "RUNNING","MAY_HAVE_STARTED","SANDBOX_RESULT_UNKNOWN",null,null,0),PATH);
        }
        repository.commitReceipt(command,receipt,instance.id()); // Stop fact must commit before cleanup.
        cleanup(command,receipt);return signed(command,receipt,PATH);
    }
    @Override public SignedResult query(String token,String operationId) {
        String path=PATH+"/"+operationId;
        var request=signatures.verifiedQuery(token,path);
        var command=repository.lookup(request.operationId());validate(command);
        signatures.verify(token,binding(command,"GET",path),new byte[0]);
        var receipt=repository.receipt(command);
        if("RUNNING".equals(receipt.state())) {
            repository.checkRunner(command,instance.id());
            Receipt observed=null;
            try {
                if("PREPARE".equals(command.kind())) observed=result(command,"COMMITTED","READY",null,
                        runtime.verifyReady(command.physicalIdentity(),()->repository.check(command)),null);
                else if(!runtime.inspect(command.physicalIdentity()).running()) observed=result(command,"COMMITTED","STOPPED",null,null,runtime.stop(command.physicalIdentity()));
            } catch(RuntimeException notProven) { /* Query never creates, starts, replays or frees resources. */ }
            if(observed!=null) {repository.commitReceipt(command,observed,instance.id());receipt=observed;}
        }
        cleanup(command,receipt);return signed(command,receipt,path);
    }
}
