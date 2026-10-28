package com.agentplatform.app.files;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;

/** A stopped receipt may settle a blocked projection; it never grants a new physical invocation. */
public final class FileReconciliation {
    private final FileRepository repository;
    private final FileRunnerPort runner;
    private final AssetStore assets;
    public FileReconciliation(FileRepository repository,FileRunnerPort runner,AssetStore assets){this.repository=repository;this.runner=runner;this.assets=assets;}
    public void reconcile(Command command) {
        repository.observeProjection(command);
        var receipt=repository.receipt(command);
        if(receipt==null)receipt="CAPTURE".equals(command.operation().kind())?runner.queryCapture(command):runner.queryFile(command);
        if(receipt==null||"UNKNOWN".equals(receipt.resultState()))return;
        try{FileObjectVerification.verify(repository,assets,command,receipt,true);}
        catch(java.io.IOException unavailable){throw new FileFailure(503,"FILE_RECONCILIATION_UNAVAILABLE");}
        repository.reconcileProjection(command,receipt);
    }
}
