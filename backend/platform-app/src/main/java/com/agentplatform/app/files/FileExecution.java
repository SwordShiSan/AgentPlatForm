package com.agentplatform.app.files;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A committed handoff allows one POST; uncertain transport is followed only by original-command queries. */
public final class FileExecution implements AutoCloseable {
    private final FileRepository repository;
    private final FileRunnerPort runner;
    private final AssetStore assets;
    private final ScheduledExecutorService timers;
    private final AtomicBoolean accepting=new AtomicBoolean(true);
    public FileExecution(FileRepository repository,FileRunnerPort runner,AssetStore assets,ScheduledExecutorService timers) {
        this.repository=repository;this.runner=runner;this.assets=assets;this.timers=timers;
    }
    public void run(Command command) {
        var valid=new AtomicBoolean(true);
        var renewal=timers.scheduleAtFixedRate(()->{
            try{repository.renew(command);}catch(RuntimeException lost){valid.set(false);}
        },10,10,TimeUnit.SECONDS);
        try {
            if(!accepting.get()){block(command);return;}
            repository.beforeCall(command);
            if("RESTORE_TREE".equals(command.operation().kind())) {
                for(var target:repository.treeFiles(command)) {
                    if(!"FILE".equals(target.kind()))continue;
                    assets.copy(target.sourceObjectKey(),target.objectKey(),target.sizeBytes(),target.sha256(),()->repository.guard(command));
                }
            }
            Receipt receipt=null;
            try{receipt="CAPTURE".equals(command.operation().kind())?
                runner.captureFile(new FileCaptureData.Command(FileCaptureData.PROTOCOL,command,repository.captureTarget(command))):runner.applyFile(command);}
            catch(RuntimeException unknown){ /* Never repeat the POST. */ }
            while(accepting.get()&&valid.get()&&Instant.now().isBefore(command.operation().deadlineAt().minusSeconds(3))) {
                if(receipt!=null) {
                    FileObjectVerification.verify(repository,assets,command,receipt,false);
                    repository.commitProjection(command,receipt);return;
                }
                TimeUnit.MILLISECONDS.sleep(500);
                try{receipt="CAPTURE".equals(command.operation().kind())?runner.queryCapture(command):runner.queryFile(command);}catch(RuntimeException pending){receipt=null;}
            }
            block(command);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();block(command);}
        catch(RuntimeException|java.io.IOException unknown){block(command);}
        finally{renewal.cancel(false);}
    }
    private void block(Command command) {
        try{repository.uncertain(command,"FILE_RESULT_UNKNOWN");}
        catch(RuntimeException unavailable){ /* Keep lease and reservation; maintenance blocks expiry, never releases it. */ }
    }
    @Override public void close(){accepting.set(false);}
}
