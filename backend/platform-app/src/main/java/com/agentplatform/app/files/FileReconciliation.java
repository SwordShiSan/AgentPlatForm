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
        if(receipt==null)receipt=runner.queryFile(command);
        if(receipt==null||"UNKNOWN".equals(receipt.resultState()))return;
        if("APPLIED".equals(receipt.resultState()))try(var content=assets.open(command.upload().objectKey(),null,null)) {
            if(content.bytes()!=command.upload().sizeBytes()||content.totalBytes()!=command.upload().sizeBytes())throw new FileFailure(503,"ASSET_IDENTITY_MISMATCH");
            var checked=new AuthorizedAssetStream(content.stream(),content.bytes(),command.upload().sha256(),()->repository.observeProjection(command));
            byte[] buffer=new byte[65536];while(checked.read(buffer)!=-1){ /* Hash current immutable object with bounded memory. */ }
        }catch(java.io.IOException unavailable){throw new FileFailure(503,"FILE_RECONCILIATION_UNAVAILABLE");}
        repository.reconcileProjection(command,receipt);
    }
}
