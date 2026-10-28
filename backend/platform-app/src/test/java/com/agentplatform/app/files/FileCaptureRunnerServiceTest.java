package com.agentplatform.app.files;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import com.agentplatform.domain.workspace.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import tools.jackson.databind.json.JsonMapper;

/** Real JOSE and application handoff, deterministic physical/object ports. */
class FileCaptureRunnerServiceTest {
    private static RunnerSignatures control,runner;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final FileRunnerRepository repository=mock(FileRunnerRepository.class);
    private final FileRuntime runtime=mock(FileRuntime.class);
    private final AssetStore assets=mock(AssetStore.class);
    private final RunnerInstance instance=new RunnerInstance(UUID.randomUUID(),"c".repeat(64),"sha256:"+"d".repeat(64),"2026-10-28T00:00:00Z");
    private Command command;private FileCaptureData.Target target;private FileCaptureRunnerService service;
    private final AtomicReference<Receipt> receipt=new AtomicReference<>();
    @BeforeAll static void keys()throws Exception {
        var a=new RSAKeyGenerator(3072).keyID("control").generate();var b=new RSAKeyGenerator(3072).keyID("runner").generate();
        control=new RunnerSignatures(a,b.toPublicJWK(),"control","runner",Clock.systemUTC());runner=new RunnerSignatures(b,a.toPublicJWK(),"runner","control",Clock.systemUTC());
    }
    @BeforeEach void fixture() {
        var op=new Execution(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"CAPTURE",null,UUID.randomUUID(),UUID.randomUUID(),1,2L,
                Instant.now().plusSeconds(120),"sha256:"+"a".repeat(64),"b".repeat(64),null,null,null,null);
        command=new Command(op,null,200001,null);UUID asset=UUID.randomUUID(),version=UUID.randomUUID();
        target=new FileCaptureData.Target(UUID.randomUUID(),UUID.randomUUID(),op.spaceId(),op.workspaceId(),op.id(),"result.txt",1024,version,asset,
                "spaces/"+op.spaceId()+"/file-version/"+asset+"/"+version+"/content");
        when(runtime.runnerInstance()).thenReturn(instance);when(runtime.policyHash()).thenReturn(op.helperPolicyHash());
        when(repository.lookup(op.spaceId(),op.workspaceId(),op.id())).thenReturn(command);when(repository.captureTarget(command)).thenReturn(target);
        when(repository.accept(eq(command),eq(instance),anyString())).thenReturn(true,false);
        when(repository.receipt(command)).thenAnswer(call->receipt.get());
        doAnswer(call->{var value=call.getArgument(2,FileRuntime.Result.class);receipt.set(new Receipt(op.commandId(),value.state(),value.resultText(),RunnerSignatures.sha256(value.resultText().getBytes(StandardCharsets.UTF_8)),
                value.stopText(),RunnerSignatures.sha256(value.stopText().getBytes(StandardCharsets.UTF_8)),instance.id(),null));return null;})
                .when(repository).recordResult(eq(command),eq(instance.id()),any());
        service=new FileCaptureRunnerService(repository,runtime,assets,runner);
    }
    private byte[] body(FileCaptureData.Target selected){return JSON.writeValueAsBytes(new FileCaptureData.Command(FileCaptureData.PROTOCOL,command,selected));}
    private String token(byte[] body){return control.sign(FileRunnerService.binding(command,"POST","/internal/file-captures/v1"),body);}
    @Test void descriptorAndStopPrecedeUploadAndCanonicalReceiptPrecedesCleanupAndReplayIsReadOnly()throws Exception {
        byte[] bytes="666".getBytes(StandardCharsets.UTF_8);String hash=RunnerSignatures.sha256(bytes);
        var stop=new FileRuntime.Stop(command.operation().commandId(),"e".repeat(64),command.operation().helperImageId(),"2026-10-28T00:00:00Z","f".repeat(64),"2026-10-28T00:00:01Z",false,true,true,Instant.now());
        when(assets.put(eq(target.objectKey()),eq(3L),eq(hash),any())).thenAnswer(call->{assertArrayEquals(bytes,call.getArgument(3,InputStream.class).readAllBytes());return new AssetStore.Identity(target.objectKey(),3,hash);});
        when(runtime.capture(eq(command),any(),any(),any())).thenAnswer(call->{
            var sink=call.getArgument(2,FileRuntime.CaptureSink.class);sink.described(3,hash);sink.stopped(stop);sink.store(3,hash,new ByteArrayInputStream(bytes));
            return new FileRuntime.Result("CAPTURED","{\"state\":\"CAPTURED\"}",JSON.writeValueAsString(stop));
        });
        var body=body(target);var response=service.operate(token(body),body);
        control.verify(response.signature(),FileRunnerService.binding(command,"RESPONSE","/internal/file-captures/v1"),response.body());
        assertEquals("CAPTURED",JSON.readValue(response.body(),Receipt.class).resultState());service.operate(token(body),body);
        verify(runtime,times(1)).capture(eq(command),any(),any(),any());verify(assets,times(1)).put(eq(target.objectKey()),eq(3L),eq(hash),any());
        var order=inOrder(repository,assets,runtime);order.verify(repository).describeCapture(command,instance.id(),3,hash);order.verify(repository).stopCapture(command,instance.id(),stop);
        order.verify(assets).put(eq(target.objectKey()),eq(3L),eq(hash),any());order.verify(repository).recordResult(eq(command),eq(instance.id()),any());order.verify(runtime).retire(command,stop);
    }
    @Test void forgedTargetAndUnsignedBodyChangesNeverReachPhysicalAcceptance() {
        var altered=new FileCaptureData.Target(target.id(),target.sourceOperationId(),target.spaceId(),target.workspaceId(),target.fileOperationId(),"other.txt",target.maxBytes(),target.versionId(),target.assetId(),target.objectKey());
        byte[] changed=body(altered);assertEquals("FILE_COMMAND_CONFLICT",assertThrows(FileFailure.class,()->service.operate(token(changed),changed)).getMessage());
        byte[] original=body(target);assertThrows(WorkspaceFailure.class,()->service.operate(token(original),changed));
        verify(repository,never()).accept(any(),any(),anyString());verify(runtime,never()).capture(any(),any(),any(),any());verifyNoInteractions(assets);
    }
    @Test void aLostResultIsQueriedWithoutAnotherPhysicalInvocation() {
        when(runtime.capture(eq(command),any(),any(),any())).thenThrow(new FileFailure(503,"FILE_CAPTURE_UNCONFIRMED"));
        byte[] body=body(target);service.operate(token(body),body);
        String path="/internal/file-captures/v1/"+command.operation().id();String query=control.sign(FileRunnerService.binding(command,"GET",path),new byte[0]);
        service.query(query,command.operation().id().toString());verify(runtime,times(1)).capture(any(),any(),any(),any());verifyNoInteractions(assets);
    }
}
