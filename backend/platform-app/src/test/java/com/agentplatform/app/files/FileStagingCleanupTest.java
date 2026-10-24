package com.agentplatform.app.files;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class FileStagingCleanupTest {
    private final FileRepository repository=mock(FileRepository.class);
    private final AssetStore assets=mock(AssetStore.class);
    private final Upload candidate=new Upload(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),
            "old.txt","CREATE",UUID.randomUUID(),null,null,UUID.randomUUID(),UUID.randomUUID(),"private-generated-key",8,"b".repeat(64),"ABORTED",null,null,null,null,2);
    @Test void deletionMustConfirmBeforeBudgetCanBeReleased() {
        when(repository.claimCleanup()).thenReturn(candidate);doThrow(new FileFailure(503,"STAGING_DELETE_UNCONFIRMED")).when(assets).deleteUncommitted(candidate.objectKey());
        assertThrows(FileFailure.class,()->new FileStagingCleanup(repository,assets).runOne());verify(repository,never()).cleanupComplete(any());
    }
    @Test void tombstonePrecedesExternalDeleteAndProjection() {
        when(repository.claimCleanup()).thenReturn(candidate);new FileStagingCleanup(repository,assets).runOne();
        var order=inOrder(repository,assets);order.verify(repository).claimCleanup();order.verify(assets).deleteUncommitted(candidate.objectKey());order.verify(repository).cleanupComplete(candidate);
    }
    @Test void noSafeCandidateCannotTouchObjectStore(){new FileStagingCleanup(repository,assets).runOne();verifyNoInteractions(assets);}
}
