package com.agentplatform.infrastructure.workspace;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.*;
import java.io.*;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

/** Bounded Docker stdout backpressure; close and actual callback completion remain separate. */
final class FileCaptureChannel extends ResultCallback.Adapter<Frame> implements DockerFileRuntime.Active {
    final CountDownLatch done=new CountDownLatch(1),ioExited;
    final PipedInputStream source;private final PipedOutputStream stdout;
    private final BooleanSupplier inputStopped;
    volatile Throwable failure;private int stderrBytes;
    FileCaptureChannel(BooleanSupplier inputStopped,CountDownLatch ioExited)throws IOException {
        this.inputStopped=inputStopped;this.ioExited=ioExited;source=new PipedInputStream(65536);stdout=new PipedOutputStream(source);
    }
    @Override public void onNext(Frame frame) {
        try {
            if(frame.getStreamType()==StreamType.STDOUT)stdout.write(frame.getPayload());
            else if(frame.getStreamType()==StreamType.STDERR){stderrBytes=Math.min(4097,stderrBytes+Math.min(4097,frame.getPayload().length));if(stderrBytes>4096&&failure==null)failure=new IOException("FILE_CAPTURE_STDERR_BOUND");}
        }catch(IOException problem){failure=problem;close();}
    }
    @Override public void onError(Throwable problem){failure=problem;ended();}
    @Override public void onComplete(){ended();}
    private void ended(){try{stdout.close();}catch(IOException problem){failure=problem;}done.countDown();}
    @Override public boolean quiescent(){return done.getCount()==0&&ioExited.getCount()==0&&inputStopped.getAsBoolean();}
    @Override public void close(){try{source.close();stdout.close();super.close();}catch(IOException problem){failure=problem;}}
}
