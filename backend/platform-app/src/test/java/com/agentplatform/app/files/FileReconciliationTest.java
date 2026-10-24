package com.agentplatform.app.files;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class FileReconciliationTest {
    private final FileRepository repository=mock(FileRepository.class);
    private final FileRunnerPort runner=mock(FileRunnerPort.class);
    private final AssetStore assets=mock(AssetStore.class);
    private final Command command=new Command(null,null,200001,null);
    private Receipt receipt(String state){return new Receipt(UUID.randomUUID(),state,"{}","a".repeat(64),"{}","b".repeat(64),UUID.randomUUID(),null);}
    @Test void responseLossOnlyQueriesAndProjectsStoredStopFacts() {
        var receipt=receipt("NOT_APPLIED");when(runner.queryFile(command)).thenReturn(receipt);
        new FileReconciliation(repository,runner,assets).reconcile(command);
        verify(repository).observeProjection(command);verify(runner).queryFile(command);verify(repository).reconcileProjection(command,receipt);
        verify(runner,never()).applyFile(any());verifyNoInteractions(assets);
    }
    @Test void unknownAndMissingFactsNeverFreeOrReexecute() {
        when(repository.receipt(command)).thenReturn(receipt("UNKNOWN"));
        new FileReconciliation(repository,runner,assets).reconcile(command);
        verify(repository,never()).reconcileProjection(any(),any());verifyNoInteractions(runner,assets);
    }
}
