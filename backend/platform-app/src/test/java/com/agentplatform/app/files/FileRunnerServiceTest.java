package com.agentplatform.app.files;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import com.agentplatform.domain.workspace.RunnerInstance;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.io.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileRunnerServiceTest {
    @Test void failedSourceCloseCannotProduceAStopReceiptOrReleaseThroughQuery() {
        var repository=mock(FileRunnerRepository.class);var runtime=mock(FileRuntime.class);var assets=mock(AssetStore.class);var signatures=mock(RunnerSignatures.class);
        var instance=new RunnerInstance(UUID.randomUUID(),"c".repeat(64),"sha256:"+"d".repeat(64),Instant.now().minusSeconds(30).toString());
        when(runtime.runnerInstance()).thenReturn(instance);when(runtime.policyHash()).thenReturn("b".repeat(64));
        var operation=new Execution(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"APPLY_UPLOAD",UUID.randomUUID(),
                UUID.randomUUID(),UUID.randomUUID(),1,1L,Instant.now().plusSeconds(120),"sha256:"+"a".repeat(64),"b".repeat(64),null,null,null,null);
        var command=new Command(operation,new Input(operation.uploadId(),operation.spaceId(),operation.workspaceId(),operation.actorId(),"a.txt","CREATE",
                UUID.randomUUID(),null,null,UUID.randomUUID(),UUID.randomUUID(),"private-generated-key",0,"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),200001,null);
        when(repository.lookup(operation.spaceId(),operation.workspaceId(),operation.id())).thenReturn(command);
        when(repository.accept(eq(command),eq(instance),any())).thenReturn(true);
        when(repository.mayObserve(command,instance.id())).thenReturn(true);
        var broken=new ByteArrayInputStream(new byte[0]){@Override public void close()throws IOException{throw new IOException("fixture close unconfirmed");}};
        when(assets.open(command.upload().objectKey(),null,null)).thenReturn(new AssetStore.Content(broken,0,0));
        when(runtime.apply(eq(command),any(),any())).thenReturn(new FileRuntime.Result("UNKNOWN","{}","{}"));
        var service=new FileRunnerService(repository,runtime,assets,signatures);
        var result=service.operate("fixture-token",JsonMapper.builder().build().writeValueAsBytes(command));assertEquals("null",new String(result.body(),java.nio.charset.StandardCharsets.UTF_8));
        String path=FileRunnerService.PATH+"/"+operation.id();when(signatures.verifiedQuery("fixture-token",path)).thenReturn(FileRunnerService.binding(command,"GET",path));
        service.query("fixture-token",operation.id().toString());
        verify(repository,never()).recordResult(any(),any(),any());verify(runtime,never()).reconcile(any(),any(),anyBoolean());
        verify(runtime,times(1)).apply(eq(command),any(),any());
    }
}
