package com.agentplatform.app.files;

import com.agentplatform.domain.files.*;

/** Only a durable tombstone for a proven unreferenced, non-active upload authorizes object deletion. */
public final class FileStagingCleanup {
    private final FileRepository repository;
    private final AssetStore assets;
    public FileStagingCleanup(FileRepository repository,AssetStore assets){this.repository=repository;this.assets=assets;}
    public void runOne() {
        var upload=repository.claimCleanup();if(upload==null)return;
        assets.deleteUncommitted(upload.objectKey()); // Adapter must verify absence in an existing private bucket.
        repository.cleanupComplete(upload);
    }
}
