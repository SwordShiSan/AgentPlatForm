package com.agentplatform.app.preview;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.preview.*;
import com.agentplatform.domain.preview.PreviewData.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Only one initial POST. Lost responses are queried by the original durable command. */
public final class PreviewExecution implements AutoCloseable {
    private final PreviewRepository repository;private final PreviewRunnerPort runner;private final AssetStore assets;private final ScheduledExecutorService timers;
    private final AtomicBoolean accepting=new AtomicBoolean(true);
    public PreviewExecution(PreviewRepository repository,PreviewRunnerPort runner,AssetStore assets,ScheduledExecutorService timers){this.repository=repository;this.runner=runner;this.assets=assets;this.timers=timers;}
    public void run(Command command) {
        var valid=new AtomicBoolean(true);var renewal=timers.scheduleAtFixedRate(()->{try{repository.renew(command);}catch(RuntimeException lost){valid.set(false);}},10,10,TimeUnit.SECONDS);
        try {
            repository.beforeCall(command);Receipt receipt=null;
            try{receipt=runner.render(command);}catch(RuntimeException uncertain){/* Query the same command, never repeat POST. */}
            while(accepting.get()&&valid.get()&&Instant.now().isBefore(command.deadlineAt())) {
                if(receipt!=null){complete(command,receipt);return;}
                TimeUnit.MILLISECONDS.sleep(500);try{receipt=runner.query(command);}catch(RuntimeException unavailable){receipt=null;}
            }
            unknown(command);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();unknown(command);}
        catch(RuntimeException uncertain){unknown(command);}
        finally{renewal.cancel(false);}
    }
    private void complete(Command command,Receipt receipt) {
        if("READY".equals(receipt.state())) {
            var identity=assets.inspect(receipt.objectKey());
            if(identity.bytes()!=receipt.bytes()||!receipt.sha256().equals(identity.sha256()))throw new FileFailure(503,"PREVIEW_ASSET_IDENTITY_MISMATCH");
        }
        repository.complete(command,receipt);
    }
    public void reconcile(Command command){var receipt=runner.query(command);if(receipt!=null)complete(command,receipt);}
    private void unknown(Command command){try{repository.unknown(command,"PREVIEW_RESULT_UNKNOWN");}catch(RuntimeException unavailable){/* Keep ownership and reservation until the stopped receipt exists. */}}
    @Override public void close(){accepting.set(false);}
}
