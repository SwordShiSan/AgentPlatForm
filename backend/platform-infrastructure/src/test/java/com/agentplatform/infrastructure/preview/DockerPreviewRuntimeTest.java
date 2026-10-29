package com.agentplatform.infrastructure.preview;

import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.domain.preview.PreviewData.Command;
import com.agentplatform.domain.preview.PreviewRuntime;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InfoCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Info;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fail-closed checks around actual Docker admission; not substitutes for runsc execution evidence. */
class DockerPreviewRuntimeTest {
    private final PreviewRuntimePolicy policy=new PreviewRuntimePolicy("sha256:"+"a".repeat(64),Path.of("/srv/agentplatform/previews"),Path.of("/nonexistent-attestation"),"preview-v1");
    private final DockerClient docker=mock(DockerClient.class);
    private Command command(){return new Command(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
            "sample.txt","TEXT","AUTO","private-object",0,"b".repeat(64),policy.image(),policy.bundle(),policy.hash(),1,UUID.randomUUID(),1,Instant.now().plusSeconds(120),"private-output");}
    private PreviewRuntime.Guard guard(PreviewRuntime.Physical physical,boolean startRequested) {
        return new PreviewRuntime.Guard(){
            public void check(){} public PreviewRuntime.Physical physical(){return physical;}public boolean startWasRequested(){return startRequested;}
            public void created(String id){}public void startRequested(){}public void started(String at){}
        };
    }
    @Test void missingRuntimeNeverFallsBackOrCreatesAContainer() {
        var info=mock(InfoCmd.class);var result=mock(Info.class);when(docker.infoCmd()).thenReturn(info);when(info.exec()).thenReturn(result);when(result.getRuntimes()).thenReturn(Map.of());
        var runtime=new DockerPreviewRuntime(policy,UUID.randomUUID(),docker);var c=command();
        var failure=assertThrows(FileFailure.class,()->runtime.render(c,Path.of("input"),Path.of("output"),guard(null,false)));
        assertEquals("PREVIEW_RUNTIME_UNAVAILABLE",failure.getMessage());verify(docker,never()).createContainerCmd(anyString());
    }
    @Test void absentPhysicalWithUncertainStartIsNotAStopProof() {
        var runtime=new DockerPreviewRuntime(policy,UUID.randomUUID(),docker);
        assertNull(runtime.reconcile(command(),Path.of("output"),guard(null,true)));verifyNoInteractions(docker);
    }
    @Test void inspect404CannotConfirmAnExistingPhysicalStopped() {
        var inspect=mock(InspectContainerCmd.class);when(docker.inspectContainerCmd("exact-id")).thenReturn(inspect);when(inspect.exec()).thenThrow(new NotFoundException("missing"));
        var runtime=new DockerPreviewRuntime(policy,UUID.randomUUID(),docker);
        assertThrows(NotFoundException.class,()->runtime.reconcile(command(),Path.of("output"),guard(new PreviewRuntime.Physical("exact-id",Instant.now().toString()),true)));
        verify(docker,never()).createContainerCmd(anyString());
    }
    @Test void lostAuthorizationStopsBeforeAnyPhysicalAdmission() {
        var authority=new PreviewRuntime.Guard(){
            public void check(){throw new FileFailure(403,"REVOKED");}public PreviewRuntime.Physical physical(){return null;}
            public void created(String id){}public void startRequested(){}public void started(String at){}
        };
        var runtime=new DockerPreviewRuntime(policy,UUID.randomUUID(),docker);
        assertThrows(FileFailure.class,()->runtime.render(command(),Path.of("input"),Path.of("output"),authority));verifyNoInteractions(docker);
    }
}
