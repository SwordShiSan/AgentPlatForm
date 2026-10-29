package com.agentplatform.app.preview;

import com.agentplatform.api.files.PreviewApi;
import com.agentplatform.domain.files.*;
import com.agentplatform.domain.preview.*;
import com.agentplatform.domain.preview.PreviewData.Job;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreviewApplicationServiceTest {
    private final PreviewRepository repository=mock(PreviewRepository.class);
    private final AssetStore assets=mock(AssetStore.class);
    private final PreviewApplicationService service=new PreviewApplicationService(repository,assets);
    private final PreviewApi.Caller caller=new PreviewApi.Caller(UUID.randomUUID().toString(),UUID.randomUUID().toString());
    private final UUID id=UUID.randomUUID();
    private Job job(String state,String format){var job=mock(Job.class);when(job.id()).thenReturn(id);when(job.state()).thenReturn(state);when(job.format()).thenReturn(format);when(job.outputObjectKey()).thenReturn("private-test-object");when(job.bytes()).thenReturn(3L);when(job.mediaType()).thenReturn("application/pdf");return job;}
    @Test void readyPdfReadsOneAuthorizedRangeAndClosesTransport()throws Exception {
        var job=job("READY","PDF");when(repository.get(any(),eq(id))).thenReturn(job);
        var input=spy(new ByteArrayInputStream(new byte[]{2,3}));when(assets.open("private-test-object",1L,2L)).thenReturn(new AssetStore.Content(input,2,3));
        try(var content=service.content(caller,id.toString(),"bytes=1-")){assertEquals(2,content.bytes());assertEquals(1L,content.first());assertArrayEquals(new byte[]{2,3},content.stream().readAllBytes());}
        verify(input).close();verify(repository,atLeast(3)).get(any(),eq(id));
    }
    @Test void blockAndMultiRangeDoNotReadAnyObject() {
        var blocked=job("BLOCKED",null);var ready=job("READY","PDF");
        when(repository.get(any(),eq(id))).thenReturn(blocked);assertEquals(409,assertThrows(FileFailure.class,()->service.content(caller,id.toString(),null)).status());
        when(repository.get(any(),eq(id))).thenReturn(ready);assertEquals(416,assertThrows(FileFailure.class,()->service.content(caller,id.toString(),"bytes=0-1,2-2")).status());verifyNoInteractions(assets);
    }
    @Test void wrongLengthClosesBeforeRejectingAndFullReadChecksActualHash()throws Exception {
        var job=job("READY","PDF");when(repository.get(any(),eq(id))).thenReturn(job);var input=spy(new ByteArrayInputStream(new byte[]{1}));
        when(assets.open("private-test-object",null,null)).thenReturn(new AssetStore.Content(input,1,1));assertThrows(FileFailure.class,()->service.content(caller,id.toString(),null));verify(input).close();
        when(job.sha256()).thenReturn("0".repeat(64));when(assets.open("private-test-object",null,null)).thenReturn(new AssetStore.Content(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)),3,3));
        try(var content=service.content(caller,id.toString(),null)){assertThrows(java.io.IOException.class,()->content.stream().readAllBytes());}
    }
}
