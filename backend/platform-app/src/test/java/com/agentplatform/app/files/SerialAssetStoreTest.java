package com.agentplatform.app.files;

import com.agentplatform.domain.files.*;
import java.io.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SerialAssetStoreTest {
    @Test void restoreCopyUsesOneGlobalPermitAndClosesBeforeRelease() throws Exception {
        var delegate=mock(AssetStore.class);var permits=mock(FileTransferPermit.class);var owner=UUID.randomUUID();
        when(permits.acquire("new")).thenReturn(owner);var source=spy(new ByteArrayInputStream(new byte[]{1,2,3}));
        when(delegate.open("old",null,null)).thenReturn(new AssetStore.Content(source,3,3));
        when(delegate.put(eq("new"),eq(3L),anyString(),any())).thenAnswer(call->{
            assertArrayEquals(new byte[]{1,2,3},((InputStream)call.getArgument(3)).readAllBytes());return new AssetStore.Identity("new",3,call.getArgument(2));});
        var guard=mock(Runnable.class);new SerialAssetStore(delegate,permits).copy("old","new",3,"039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",guard);
        verify(permits,times(1)).acquire("new");verify(permits,never()).acquire("old");
        var order=inOrder(source,permits);order.verify(source).close();order.verify(permits).release(owner);verify(guard,atLeastOnce()).run();
    }
    @Test void openRetainsGlobalPermitUntilActualStreamClose() throws Exception {
        var delegate=mock(AssetStore.class);var permits=mock(FileTransferPermit.class);var owner=UUID.randomUUID();
        when(permits.acquire("key")).thenReturn(owner);when(delegate.open("key",null,null)).thenReturn(new AssetStore.Content(new ByteArrayInputStream(new byte[0]),0,0));
        var result=new SerialAssetStore(delegate,permits).open("key",null,null);
        verify(permits,never()).release(any());assertEquals(-1,result.stream().read());verify(permits,never()).release(any());
        result.close();result.close();verify(permits,times(1)).release(owner);
    }
    @Test void unconfirmedCloseRetainsPermitInsteadOfPretendingStopped() {
        var delegate=mock(AssetStore.class);var permits=mock(FileTransferPermit.class);var owner=UUID.randomUUID();
        when(permits.acquire("key")).thenReturn(owner);
        var stream=new ByteArrayInputStream(new byte[0]){@Override public void close()throws IOException{throw new IOException("close unconfirmed");}};
        when(delegate.open("key",null,null)).thenReturn(new AssetStore.Content(stream,0,0));
        var result=new SerialAssetStore(delegate,permits).open("key",null,null);
        assertThrows(IOException.class,result::close);assertThrows(IOException.class,result::close);verify(permits,never()).release(any());
    }
    @Test void rejectedTransportNeverTouchesDelegateAndSynchronousFailureReleases() {
        var delegate=mock(AssetStore.class);var permits=mock(FileTransferPermit.class);var store=new SerialAssetStore(delegate,permits);
        when(permits.acquire("key")).thenThrow(new FileFailure(423,"FILE_TRANSFER_BUSY"));
        assertThrows(FileFailure.class,()->store.open("key",null,null));verifyNoInteractions(delegate);
        var owner=UUID.randomUUID();doReturn(owner).when(permits).acquire("key");
        when(delegate.inspect("key")).thenThrow(new FileFailure(503,"UNAVAILABLE"));
        assertThrows(FileFailure.class,()->store.inspect("key"));verify(permits).release(owner);
    }
    @Test void databaseRecoveryRetriesOnlyStoppedReleaseWithoutRepeatingOldTransfer() {
        var delegate=mock(AssetStore.class);var permits=mock(FileTransferPermit.class);UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        when(permits.acquire("first")).thenReturn(first);when(permits.acquire("second")).thenReturn(second);
        doThrow(new FileFailure(503,"DB_UNAVAILABLE")).doNothing().when(permits).release(first);
        var store=new SerialAssetStore(delegate,permits);assertThrows(FileFailure.class,()->store.inspect("first"));
        store.inspect("second");verify(delegate,times(1)).inspect("first");verify(delegate).inspect("second");
        verify(permits,times(2)).release(first);verify(permits).release(second);
    }
    @Test void runnerStreamCloseCannotReleaseWithoutPersistedHelperStopReceipt()throws Exception {
        var delegate=mock(AssetStore.class);var permits=mock(FileTransferPermit.class);var owner=UUID.randomUUID();
        when(permits.acquire("key")).thenReturn(owner);when(delegate.open("key",null,null)).thenReturn(new AssetStore.Content(new ByteArrayInputStream(new byte[0]),0,0));
        var store=new SerialAssetStore(delegate,permits,true);var content=store.open("key",null,null);content.close();content.close();store.flushStoppedTransfers();
        verify(permits,never()).release(any());
    }
    @Test void timerCanRetryOnlyItsOwnAlreadyStoppedReleaseWithoutAnotherObjectRequest() {
        var delegate=mock(AssetStore.class);var permits=mock(FileTransferPermit.class);var owner=UUID.randomUUID();
        when(permits.acquire("key")).thenReturn(owner);doThrow(new FileFailure(503,"DB_DOWN")).doNothing().when(permits).release(owner);
        var store=new SerialAssetStore(delegate,permits);assertThrows(FileFailure.class,()->store.inspect("key"));
        store.flushStoppedTransfers();store.flushStoppedTransfers();
        verify(delegate,times(1)).inspect("key");verify(permits,times(1)).acquire("key");verify(permits,times(2)).release(owner);
    }
    @Test void closeWaitsForTheActualReaderToExitBeforeReleasing()throws Exception {
        var delegate=mock(AssetStore.class);var permits=mock(FileTransferPermit.class);var owner=UUID.randomUUID();
        var entered=new java.util.concurrent.CountDownLatch(1);var exit=new java.util.concurrent.CountDownLatch(1);
        var source=new InputStream(){@Override public int read()throws IOException{
            entered.countDown();try{if(!exit.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("fixture reader timeout");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}return -1;
        }};
        when(permits.acquire("key")).thenReturn(owner);when(delegate.open("key",null,null)).thenReturn(new AssetStore.Content(source,0,0));
        var content=new SerialAssetStore(delegate,permits).open("key",null,null);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future=executor.submit(()->content.stream().read());assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
            content.close();verify(permits,never()).release(any());exit.countDown();assertEquals(-1,future.get(3,java.util.concurrent.TimeUnit.SECONDS));
            verify(permits).release(owner);
        }finally{exit.countDown();executor.shutdownNow();}
    }
}
