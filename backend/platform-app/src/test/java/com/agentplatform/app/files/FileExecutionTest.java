package com.agentplatform.app.files;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileExecutionTest {
    private final FileRepository repository=mock(FileRepository.class);
    private final FileRunnerPort runner=mock(FileRunnerPort.class);
    private final AssetStore assets=mock(AssetStore.class);
    private final ScheduledExecutorService timers=Executors.newSingleThreadScheduledExecutor();
    private final UUID operation=UUID.randomUUID(),space=UUID.randomUUID(),workspace=UUID.randomUUID(),actor=UUID.randomUUID(),upload=UUID.randomUUID();
    private Command command(Instant deadline) {
        return new Command(new Execution(operation,space,workspace,actor,"APPLY_UPLOAD",upload,UUID.randomUUID(),UUID.randomUUID(),1,1L,
                deadline,"sha256:"+"a".repeat(64),"b".repeat(64),null,null,null,null),
                new Input(upload,space,workspace,actor,"hello.txt","CREATE",UUID.randomUUID(),null,null,UUID.randomUUID(),UUID.randomUUID(),"private-key",0,
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),200001,null);
    }
    private Receipt receipt(Command c,String state){return new Receipt(c.operation().commandId(),state,"{}","a".repeat(64),"{}","b".repeat(64),UUID.randomUUID(),null);}
    @AfterEach void stop(){timers.shutdownNow();}
    private FileExecution execution(){return new FileExecution(repository,runner,assets,timers);}
    @Test void lostPostResponseQueriesOriginalCommandWithoutSecondWrite() {
        var c=command(Instant.now().plusSeconds(15));var fact=receipt(c,"NOT_APPLIED");
        when(runner.applyFile(c)).thenThrow(new FileFailure(503,"RESPONSE_LOST"));when(runner.queryFile(c)).thenReturn(fact);
        execution().run(c);
        var order=inOrder(repository,runner);order.verify(repository).beforeCall(c);order.verify(runner).applyFile(c);
        order.verify(runner).queryFile(c);order.verify(repository).commitProjection(c,fact);
        verify(runner,times(1)).applyFile(c);verify(repository,never()).uncertain(any(),any());verifyNoInteractions(assets);
    }
    @Test void successfulPhysicalResultRequiresIndependentObjectHash() throws Exception {
        var c=command(Instant.now().plusSeconds(15));var fact=receipt(c,"APPLIED");when(runner.applyFile(c)).thenReturn(fact);
        when(assets.open("private-key",null,null)).thenReturn(new AssetStore.Content(new ByteArrayInputStream(new byte[0]),0,0));
        execution().run(c);verify(repository).commitProjection(c,fact);verify(repository,never()).uncertain(any(),any());
    }
    @Test void invalidObjectDoesNotPublishVersionOrRefund() {
        var c=command(Instant.now().plusSeconds(15));when(runner.applyFile(c)).thenReturn(receipt(c,"APPLIED"));
        when(assets.open("private-key",null,null)).thenReturn(new AssetStore.Content(new ByteArrayInputStream(new byte[]{1}),1,1));
        execution().run(c);verify(repository,never()).commitProjection(any(),any());verify(repository).uncertain(c,"FILE_RESULT_UNKNOWN");
    }
    @Test void lostOwnershipBlocksWithoutProviderOrSecondPost() {
        var c=command(Instant.now().plusSeconds(15));doThrow(new FileFailure(409,"FILE_STALE_OWNER")).when(repository).beforeCall(c);
        execution().run(c);verifyNoInteractions(runner,assets);verify(repository).uncertain(c,"FILE_RESULT_UNKNOWN");
    }
    @Test void unknownReceiptUsesFactTransactionAndNeverReadsObject() {
        var c=command(Instant.now().plusSeconds(15));var fact=receipt(c,"UNKNOWN");when(runner.applyFile(c)).thenReturn(fact);
        execution().run(c);verify(repository).commitProjection(c,fact);verifyNoInteractions(assets);verify(runner,never()).queryFile(any());
    }
    @Test void shutdownCannotDispatchAnotherPhysicalOperation() {
        var c=command(Instant.now().plusSeconds(15));var execution=execution();execution.close();execution.run(c);
        verifyNoInteractions(runner,assets);verify(repository,never()).beforeCall(any());verify(repository).uncertain(c,"FILE_RESULT_UNKNOWN");
    }
}
