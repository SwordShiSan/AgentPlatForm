package com.agentplatform.infrastructure.workspace;

import com.github.dockerjava.api.model.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileCaptureChannelTest {
    @Test void stdoutBackpressureDoesNotAccumulateAnUnboundedPayloadAndTerminationRequiresAllParticipants()throws Exception {
        var inputStopped=new AtomicBoolean();var ioExited=new CountDownLatch(1);
        try(var channel=new FileCaptureChannel(inputStopped::get,ioExited);var workers=Executors.newSingleThreadExecutor()) {
            byte[] payload=new byte[131072];java.util.Arrays.fill(payload,(byte)42);
            var entered=new CountDownLatch(1);
            var writing=workers.submit(()->{entered.countDown();channel.onNext(new Frame(StreamType.STDOUT,payload));channel.onComplete();});
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->writing.get(100,TimeUnit.MILLISECONDS));
            assertArrayEquals(payload,channel.source.readNBytes(payload.length));writing.get(2,TimeUnit.SECONDS);assertEquals(-1,channel.source.read());
            assertFalse(channel.quiescent());inputStopped.set(true);assertFalse(channel.quiescent());ioExited.countDown();assertTrue(channel.quiescent());
        }
    }
    @Test void closingTransportIsNotCallbackOrIoExitEvidence()throws Exception {
        var ioExited=new CountDownLatch(1);var channel=new FileCaptureChannel(()->true,ioExited);
        channel.close();assertFalse(channel.quiescent());
        channel.onError(new java.io.IOException("transport failed"));assertFalse(channel.quiescent());
        ioExited.countDown();assertTrue(channel.quiescent());assertNotNull(channel.failure);
    }
    @Test void stderrHasABoundedDiagnosticSignalWithoutRetainingRawPayload()throws Exception {
        try(var channel=new FileCaptureChannel(()->true,new CountDownLatch(0))) {
            channel.onNext(new Frame(StreamType.STDERR,new byte[4097]));assertNotNull(channel.failure);
            assertEquals("FILE_CAPTURE_STDERR_BOUND",channel.failure.getMessage());
        }
    }
}
