package com.agentplatform.app.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agentplatform.api.knowledge.KnowledgeApi;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.domain.knowledge.KnowledgeEncoder;
import com.agentplatform.domain.knowledge.KnowledgeRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class KnowledgeApplicationServiceTest {
    private static final UUID USER=UUID.randomUUID(), SPACE=UUID.randomUUID(), BASE=UUID.randomUUID();
    private static KnowledgeApi.Caller caller(){return new KnowledgeApi.Caller(USER.toString(),SPACE.toString());}
    private static KnowledgeApi.Search query(boolean required){return new KnowledgeApi.Search("contract search",List.of(BASE.toString()),8,required);}

    @Test void semanticSearchOwnsTheSharedHeavySlotUntilResultsAreRead() {
        var repository=mock(KnowledgeRepository.class);var encoder=mock(KnowledgeEncoder.class);
        when(repository.acquireQuerySlot(any(),any(),any())).thenReturn(9L);
        when(repository.querySlotCurrent(any(),any(),any(),eq(9L))).thenReturn(true);
        when(encoder.embed("contract search",true)).thenReturn(new float[384]);

        var result=new KnowledgeApplicationService(repository,encoder).search(caller(),query(true));

        assertTrue(result.semanticAvailable());
        verify(encoder).embed("contract search",true);
        verify(repository).search(any(),eq(List.of(BASE)),eq("contract search"),anyString(),eq(8));
        verify(repository).releaseQuerySlot(any(),any(),eq(9L));
        verifyNoMoreInteractions(encoder);
    }

    @Test void unavailableSlotRejectsBeforeRunningVectorInference() {
        var repository=mock(KnowledgeRepository.class);var encoder=mock(KnowledgeEncoder.class);
        when(repository.acquireQuerySlot(any(),any(),any())).thenReturn(-1L);

        var failure=assertThrows(FileFailure.class,()->new KnowledgeApplicationService(repository,encoder).search(caller(),query(true)));

        assertEquals(423,failure.status());
        assertEquals("HEAVY_RUNTIME_BUSY",failure.getMessage());
        verifyNoInteractions(encoder);
        verify(repository,never()).search(any(),any(),anyString(),any(),anyInt());
        verify(repository,never()).releaseQuerySlot(any(),any(),anyLong());
    }

    @Test void lostFenceAfterEmbeddingPreventsSearchAndStillReleasesOnlyItsSlot() {
        var repository=mock(KnowledgeRepository.class);var encoder=mock(KnowledgeEncoder.class);
        when(repository.acquireQuerySlot(any(),any(),any())).thenReturn(4L);
        when(repository.querySlotCurrent(any(),any(),any(),eq(4L))).thenReturn(false);
        when(encoder.embed("contract search",true)).thenReturn(new float[384]);

        var failure=assertThrows(FileFailure.class,()->new KnowledgeApplicationService(repository,encoder).search(caller(),query(true)));

        assertEquals("KNOWLEDGE_SLOT_LOST",failure.getMessage());
        verify(encoder).embed("contract search",true);
        verify(repository,never()).search(any(),any(),anyString(),any(),anyInt());
        verify(repository).releaseQuerySlot(any(),any(),eq(4L));
    }
}
