package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.workspace.*;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SandboxRemovalTest {
    private final String image="sha256:"+"a".repeat(64);
    private final SandboxRuntime.Identity identity=new SandboxRuntime.Identity(UUID.randomUUID(),UUID.randomUUID(),
            UUID.randomUUID(),UUID.randomUUID(),1,1,200001);
    private final String container="c".repeat(64);
    private final String name="ap-sandbox-"+identity.environmentId()+"-1";
    private final SandboxPolicy policy=new SandboxPolicy(image,"sha256:"+"b".repeat(64),
            "/srv/agentplatform/acceptance/r34-removal/workspaces",UUID.randomUUID(),"/dev/loop1",Path.of("attestation.json"));
    private SandboxRuntime.Stop stopped() {
        return new SandboxRuntime.Stop(container,image,"2020-01-01T00:00:00Z","2020-01-01T00:00:01Z",true,true,Instant.now());
    }
    private DockerSandboxRuntime runtime(DockerClient docker) {
        return new DockerSandboxRuntime(policy,WorkspaceEffectBoundary.NONE,UUID.randomUUID(),new AuxiliaryContainerSlot(),docker);
    }
    private void absent(DockerClient docker,String target) {
        var command=mock(InspectContainerCmd.class);when(docker.inspectContainerCmd(target)).thenReturn(command);
        when(command.exec()).thenThrow(new NotFoundException("fixture absent"));
    }
    @Test void bareAbsenceWithoutDurableStopCannotReleaseCapacity() {
        var docker=mock(DockerClient.class);
        assertEquals("SANDBOX_STOP_UNCONFIRMED",assertThrows(WorkspaceFailure.class,()->runtime(docker).confirmRemoval(identity,null)).code());
        verifyNoInteractions(docker);
    }
    @Test void lostRemoveResponseCanConvergeOnlyAfterExactStopAndBothAbsenceChecks() {
        var docker=mock(DockerClient.class);absent(docker,name);absent(docker,container);
        var result=runtime(docker).confirmRemoval(identity,stopped());
        assertEquals(container,result.containerId());assertEquals(image,result.image());
        verify(docker).inspectContainerCmd(container);verify(docker,times(2)).inspectContainerCmd(name);
        verify(docker,never()).removeContainerCmd(anyString());
    }
    @Test void remainingExactContainerDoesNotBecomeCleanupSuccess() {
        var docker=mock(DockerClient.class);absent(docker,name);
        var inspect=mock(InspectContainerCmd.class);when(docker.inspectContainerCmd(container)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(mock(InspectContainerResponse.class));
        assertEquals("SANDBOX_CLEANUP_UNCONFIRMED",assertThrows(WorkspaceFailure.class,()->runtime(docker).confirmRemoval(identity,stopped())).code());
        verify(docker,never()).removeContainerCmd(anyString());
    }
    @Test void openCallbacksOrWrongImageCannotBeTurnedIntoRemovalProof() {
        for(var receipt:java.util.List.of(
                new SandboxRuntime.Stop(container,image,"2020-01-01T00:00:00Z","2020-01-01T00:00:01Z",true,false,Instant.now()),
                new SandboxRuntime.Stop(container,"sha256:"+"d".repeat(64),"2020-01-01T00:00:00Z","2020-01-01T00:00:01Z",true,true,Instant.now()))) {
            var docker=mock(DockerClient.class);
            assertThrows(WorkspaceFailure.class,()->runtime(docker).confirmRemoval(identity,receipt));verifyNoInteractions(docker);
        }
    }
}
