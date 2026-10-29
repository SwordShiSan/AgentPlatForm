package com.agentplatform.app.files;

import com.agentplatform.api.files.FileApi;
import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileHistoryServiceTest {
    @Test void historyUsesLookaheadAndDoesNotExposePrivateAssetKeys() {
        var repository=mock(FileRepository.class);var assets=mock(AssetStore.class);
        var caller=new FileApi.Caller(UUID.randomUUID().toString(),UUID.randomUUID().toString());
        var actor=new Actor(UUID.fromString(caller.userId()),UUID.fromString(caller.spaceId()));
        UUID file=UUID.randomUUID(),first=UUID.randomUUID(),second=UUID.randomUUID();
        when(repository.versions(actor,file,null,2)).thenReturn(List.of(
                new HistoricalVersion(first,file,"a".repeat(64),3,Instant.EPOCH,null),
                new HistoricalVersion(second,file,"b".repeat(64),4,Instant.EPOCH,first)));
        var result=new FileApplicationService(repository,assets).versions(caller,file.toString(),null,1);
        assertEquals(first.toString(),result.nextCursor());assertEquals(1,result.items().size());
        assertEquals(first.toString(),result.items().getFirst().id());verifyNoInteractions(assets);
    }
    @Test void invalidPageCannotReachPersistence() {
        var repository=mock(FileRepository.class);
        var service=new FileApplicationService(repository,mock(AssetStore.class));
        assertEquals(400,assertThrows(FileFailure.class,()->service.versions(null,"invalid",null,101)).status());
        verifyNoInteractions(repository);
    }
    @Test void authorizationFailureIsNotConvertedIntoEmptyHistory() {
        var repository=mock(FileRepository.class);
        var caller=new FileApi.Caller(UUID.randomUUID().toString(),UUID.randomUUID().toString());
        when(repository.versions(any(),any(),any(),anyInt())).thenThrow(new FileFailure(403,"FILE_AUTHORIZATION_REVOKED"));
        assertEquals(403,assertThrows(FileFailure.class,()->new FileApplicationService(repository,mock(AssetStore.class))
                .versions(caller,UUID.randomUUID().toString(),null,10)).status());
    }
}
