package com.agentplatform.app.workspace;

import com.agentplatform.api.workspace.RunnerControlApi;
import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.WorkspaceData.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

public final class RunnerControlService implements RunnerControlApi {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final WorkspaceRepository repository;
    private final RunnerSignatures signatures;
    private final WorkspaceAllocator allocator;
    private final WorkspaceFixtureBarrier barrier;
    public RunnerControlService(WorkspaceRepository repository,RunnerSignatures signatures,WorkspaceAllocator allocator) {
        this(repository,signatures,allocator,WorkspaceFixtureBarrier.none());
    }
    RunnerControlService(WorkspaceRepository repository,RunnerSignatures signatures,WorkspaceAllocator allocator,WorkspaceFixtureBarrier barrier) {
        this.repository=repository;this.signatures=signatures;this.allocator=allocator;this.barrier=barrier;
    }
    public static RunnerSignatures.Binding binding(Command c,String method,String path) {
        return new RunnerSignatures.Binding(method,path,c.commandId(),c.operationId(),c.spaceId(),c.workspaceId(),c.generation(),c.fence());
    }
    private SignedResult signed(Command command,Receipt receipt,String path) {
        byte[] body=JSON.writeValueAsBytes(receipt);
        return new SignedResult(body,signatures.sign(binding(command,"RESPONSE",path),body));
    }
    private void releaseHelper(Command command,Receipt receipt) {
        if(!java.util.Set.of("COMMITTED","FAILED").contains(receipt.state()))return;
        try{allocator.releaseCompletedHelper(command);}
        catch(RuntimeException pending){System.getLogger("platform.workspace.runner").log(System.Logger.Level.WARNING,"Definite receipt retained; helper cleanup pending");}
    }
    @Override public SignedResult provision(String token,byte[] body) {
        signatures.authenticate(token);
        if(body==null||body.length>65536)throw new WorkspaceFailure(413,"RUNNER_BODY_TOO_LARGE");
        Command command;
        try {command=JSON.readValue(body,Command.class);}
        catch(RuntimeException invalid){throw new WorkspaceFailure(400,"INVALID_RUNNER_COMMAND");}
        if(command==null||command.commandId()==null||command.operationId()==null||command.spaceId()==null||command.workspaceId()==null
                ||command.actorId()==null||command.ownerId()==null||command.deadline()==null||command.generation()<1||command.fence()<1
                ||command.quotaBytes()!=WorkspaceData.QUOTA_BYTES||command.quotaInodes()!=WorkspaceData.QUOTA_INODES||command.projectId()<200001
                ||!"PROVISION".equals(command.kind()))throw new WorkspaceFailure(400,"INVALID_RUNNER_COMMAND");
        signatures.verify(token,binding(command,"POST","/internal/operations"),body);
        var actual=repository.lookup(command.operationId());
        if(!actual.equals(command))throw new WorkspaceFailure(409,"RUNNER_COMMAND_CONFLICT");
        Receipt previous=repository.accept(command,RunnerSignatures.sha256(body));
        if(previous!=null){releaseHelper(command,previous);return signed(command,previous,"/internal/operations");}
        Receipt result;
        try {
            repository.check(command);
            String hash=allocator.provision(command);
            result=new Receipt(command.commandId(),command.operationId(),command.workspaceId(),command.generation(),command.fence(),"COMMITTED","ALLOCATED",null,hash,1);
        } catch(WorkspaceAllocator.RejectedBeforeEffect rejected) {
            result=new Receipt(command.commandId(),command.operationId(),command.workspaceId(),command.generation(),command.fence(),"FAILED","NOT_STARTED",rejected.getMessage(),null,1);
        } catch(RuntimeException unknown) {
            result=new Receipt(command.commandId(),command.operationId(),command.workspaceId(),command.generation(),command.fence(),"UNKNOWN","MAY_HAVE_STARTED","WORKSPACE_PROVISIONING_UNKNOWN",null,1);
        }
        if(java.util.Set.of("COMMITTED","FAILED").contains(result.state()))barrier.await("helper-exited-before-receipt");
        String json=JSON.writeValueAsString(result);
        repository.commitReceipt(command,result,json,RunnerSignatures.sha256(json.getBytes(StandardCharsets.UTF_8)));
        releaseHelper(command,result);
        return signed(command,result,"/internal/operations");
    }
    @Override public SignedResult query(String token,String operation) {
        var jwt=signatures.authenticate(token);
        UUID id=WorkspaceApplicationService.id(operation);
        if(!id.toString().equals(jwt.getClaimAsString("operation")))throw new WorkspaceFailure(401,"RUNNER_COMMAND_BINDING_REJECTED");
        var command=repository.lookup(id);String path="/internal/operations/"+operation;
        signatures.verify(token,binding(command,"GET",path),new byte[0]);
        Receipt receipt=repository.receipt(command);
        if ("RUNNING".equals(receipt.state())) {
            var observed=allocator.inspect(command);
            if(observed!=null) {
                repository.check(command);
                Receipt recovered=new Receipt(command.commandId(),command.operationId(),command.workspaceId(),command.generation(),command.fence(),
                        observed.state(),observed.effectState(),observed.errorCode(),observed.allocationHash(),1);
                String json=JSON.writeValueAsString(recovered);
                repository.commitReceipt(command,recovered,json,RunnerSignatures.sha256(json.getBytes(StandardCharsets.UTF_8)));
                receipt=recovered;
            }
        }
        releaseHelper(command,receipt);
        return signed(command,receipt,path);
    }
}
