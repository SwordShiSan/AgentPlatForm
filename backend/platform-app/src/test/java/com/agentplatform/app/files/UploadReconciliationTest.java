package com.agentplatform.app.files;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import java.io.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadReconciliationTest {
    private static final byte[] BODY="hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private Upload upload() throws Exception {
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BODY));
        return new Upload(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"b".repeat(64),
                "hello.txt","CREATE",UUID.randomUUID(),null,null,UUID.randomUUID(),UUID.randomUUID(),"original-object",5,hash,
                "VERIFYING",null,null,null,Instant.now().plusSeconds(86400),3);
    }
    private void candidate(FileRepository repository,Upload upload) {
        when(repository.uploadReconciliations(null)).thenReturn(List.of(upload));
        when(repository.fenceUploadReconciliation(upload)).thenReturn(upload);
        when(repository.uploadReconciliations(upload.id())).thenReturn(List.of());
    }
    @Test void completeHashAndConfirmedClosePrecedeSameUploadVerification() throws Exception {
        var repository=mock(FileRepository.class);var assets=mock(AssetStore.class);var upload=upload();candidate(repository,upload);
        var closed=new AtomicBoolean();var stream=new ByteArrayInputStream(BODY){@Override public void close(){closed.set(true);}};
        when(assets.open(upload.objectKey(),null,null)).thenReturn(new AssetStore.Content(stream,5,5));
        doAnswer(call->{assertTrue(closed.get());return null;}).when(repository).confirmRecoveredUpload(upload);
        new UploadReconciliation(repository,assets).runOne();verify(repository).confirmRecoveredUpload(upload);
        verify(repository,atLeastOnce()).guardUploadReconciliation(upload);
        verify(assets,never()).put(any(),anyLong(),any(),any());verify(assets,never()).deleteUncommitted(any());
        verify(repository,never()).completeUpload(any(),any());verify(repository,never()).cleanupComplete(any());
    }
    @Test void missingBusyWrongHashAndUnconfirmedCloseNeverVerifyOrReleaseReservation() throws Exception {
        for(String fault:List.of("MISSING","BUSY","HASH","CLOSE")) {
            var repository=mock(FileRepository.class);var assets=mock(AssetStore.class);var upload=upload();candidate(repository,upload);
            if(fault.equals("MISSING")||fault.equals("BUSY"))
                when(assets.open(upload.objectKey(),null,null)).thenThrow(new FileFailure(fault.equals("BUSY")?423:404,fault));
            else {
                var stream=new ByteArrayInputStream(fault.equals("HASH")?"wrong".getBytes():BODY){
                    @Override public void close()throws IOException{if(fault.equals("CLOSE"))throw new IOException("unconfirmed close");}
                };
                when(assets.open(upload.objectKey(),null,null)).thenReturn(new AssetStore.Content(stream,5,5));
            }
            var reconciliation=new UploadReconciliation(repository,assets);
            reconciliation.runOne();reconciliation.runOne();reconciliation.runOne();
            verify(assets,times(1)).open(upload.objectKey(),null,null);verify(repository,never()).confirmRecoveredUpload(any());
            verify(assets,never()).put(any(),anyLong(),any(),any());verify(assets,never()).deleteUncommitted(any());
            verify(repository,never()).cleanupComplete(any());verify(repository,never()).completeUpload(any(),any());
        }
    }
    @Test void revocationAndRevisionRaceRemainPendingWithoutAnObjectWrite() throws Exception {
        for(boolean revoked:List.of(true,false)) {
            var repository=mock(FileRepository.class);var assets=mock(AssetStore.class);var upload=upload();candidate(repository,upload);
            when(assets.open(upload.objectKey(),null,null)).thenReturn(new AssetStore.Content(new ByteArrayInputStream(BODY),5,5));
            if(revoked)doThrow(new FileFailure(403,"REVOKED")).when(repository).guardUploadReconciliation(upload);
            else doThrow(new FileFailure(409,"REVISION_CHANGED")).when(repository).confirmRecoveredUpload(upload);
            new UploadReconciliation(repository,assets).runOne();
            if(revoked)verify(repository,never()).confirmRecoveredUpload(any());
            verify(assets,never()).put(any(),anyLong(),any(),any());verify(assets,never()).deleteUncommitted(any());
        }
    }
}
