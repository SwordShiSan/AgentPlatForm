package com.agentplatform.app.files;

import com.agentplatform.domain.files.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Positive-only original-object reads. Never retries a PUT, refunds or queues a file effect. */
public final class UploadReconciliation {
    private final FileRepository repository;
    private final AssetStore assets;
    private UUID after;
    private final Map<UUID,Long> retryAfter=new LinkedHashMap<>(64,0.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID,Long> entry){return size()>64;}
    };
    public UploadReconciliation(FileRepository repository,AssetStore assets){this.repository=repository;this.assets=assets;}
    public void runOne() {
        var candidates=repository.uploadReconciliations(after);
        if(candidates.isEmpty()){after=null;return;}
        for(var observed:candidates) {
            after=observed.id();long now=System.nanoTime();Long retry=retryAfter.get(observed.id());
            if(retry!=null&&retry-now>0)continue;
            try {
                var fenced=repository.fenceUploadReconciliation(observed);
                // The adapter's durable single-transfer admission must be available;
                // fencing an expired upload never releases an old transport slot.
                try(var content=assets.open(fenced.objectKey(),null,null)) {
                    if(content.bytes()!=fenced.sizeBytes()||content.totalBytes()!=fenced.sizeBytes())
                        throw new FileFailure(503,"ASSET_IDENTITY_MISMATCH");
                    var checked=new AuthorizedAssetStream(content.stream(),fenced.sizeBytes(),fenced.sha256(),
                            ()->repository.guardUploadReconciliation(fenced));
                    byte[] buffer=new byte[65536];while(checked.read(buffer)!=-1){ /* bounded complete hash */ }
                }
                repository.confirmRecoveredUpload(fenced);retryAfter.remove(fenced.id());
            } catch(RuntimeException|java.io.IOException pending) {
                // Absence, mismatch, revocation, busy slot and failed close are not no-effect proofs.
                retryAfter.put(observed.id(),System.nanoTime()+TimeUnit.MINUTES.toNanos(1));
            }
            return; // At most one physical object read per timer invocation.
        }
    }
}
