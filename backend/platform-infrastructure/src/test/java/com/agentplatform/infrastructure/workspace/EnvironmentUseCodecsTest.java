package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.EnvironmentUseData.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentUseCodecsTest {
    private final UUID id=UUID.fromString("0199e000-0000-7000-8000-000000000001");
    private Command command(UseRequest request){return new Command(id,id,id,id,id,id,1,2,id,1,200001,"sha256:"+"a".repeat(64),"b".repeat(64),"c".repeat(64),"2026-10-27T00:00:00Z",request.kind(),"d".repeat(64),request,Instant.parse("2026-10-27T00:01:00Z"),Instant.parse("2026-10-27T00:02:00Z"));}
    /** Pre-R4.4 field declaration, retaining the old concrete request type. */
    private record LegacyWire(UUID id,UUID spaceId,UUID workspaceId,UUID environmentId,UUID actorId,UUID ownerId,
            long generation,long fence,UUID creationCommandId,long creationFence,int projectId,String imageId,String policyHash,
            String containerId,String containerStartedAt,String kind,String requestHash,Request request,Instant executeUntil,Instant deadline) {
        LegacyWire(Command c){this(c.id(),c.spaceId(),c.workspaceId(),c.environmentId(),c.actorId(),c.ownerId(),c.generation(),c.fence(),c.creationCommandId(),c.creationFence(),c.projectId(),c.imageId(),c.policyHash(),c.containerId(),c.containerStartedAt(),c.kind(),c.requestHash(),(Request)c.request(),c.executeUntil(),c.deadline());}
    }
    @Test void legacyCommandAndRequestRetainTheirExactJsonAndHash() {
        var codec=EnvironmentUseCodecs.legacy();var request=new Request(new SandboxRuntime.FileRequest(SandboxRuntime.FileAction.WRITE,"a.txt","NjY2"),null);
        assertEquals("{\"file\":{\"action\":\"WRITE\",\"path\":\"a.txt\",\"contentBase64\":\"NjY2\"},\"probe\":null}",codec.writeValueAsString(request));
        var command=command(request);byte[] original=codec.writeValueAsBytes(new LegacyWire(command)),current=codec.writeValueAsBytes(command);
        assertArrayEquals(original,current);assertEquals(RunnerSignatures.sha256(original),RunnerSignatures.sha256(current));
        assertEquals(command,codec.readValue(original,Command.class));assertEquals(request,EnvironmentUseCodecs.stored(request.kind(),codec.writeValueAsString(request)));
    }
    @Test void shellHasASeparateConcreteFamilyAndCannotBeReadOnTheLegacyRoute() {
        var parent=new WorkspaceShellData.Parent(id,id,id,id,3,4,"e".repeat(64),5,6);
        var request=new WorkspaceShellData.Request(WorkspaceShellData.PROTOCOL,parent,new SandboxRuntime.ShellRequest("printf 666","",20));
        var shell=EnvironmentUseCodecs.shell();var command=command(request);byte[] json=shell.writeValueAsBytes(command);
        assertEquals(command,shell.readValue(json,Command.class));assertThrows(RuntimeException.class,()->EnvironmentUseCodecs.legacy().readValue(json,Command.class));
        byte[] legacy=EnvironmentUseCodecs.legacy().writeValueAsBytes(command(new Request(null,SandboxRuntime.Probe.PYTHON_666)));
        assertThrows(RuntimeException.class,()->shell.readValue(legacy,Command.class));
        assertEquals(request,EnvironmentUseCodecs.stored("SHELL",shell.writeValueAsString(request)));
        assertThrows(RuntimeException.class,()->EnvironmentUseCodecs.stored("FILE_READ",shell.writeValueAsString(request)));
    }
}
