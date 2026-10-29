package com.agentplatform.app.files;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.*;

/** Independent object read-back before publishing an upload or captured version. */
final class FileObjectVerification {
    private FileObjectVerification() {}
    static void verify(FileRepository repository,AssetStore assets,Command command,Receipt receipt,boolean recovery)throws java.io.IOException {
        if("RESTORE_TREE".equals(command.operation().kind())&&"MUTATED".equals(receipt.resultState())) {
            for(var file:repository.treeFiles(command)) {
                if(!"FILE".equals(file.kind()))continue;
                try(var content=assets.open(file.objectKey(),null,null)) {
                    if(content.bytes()!=file.sizeBytes()||content.totalBytes()!=file.sizeBytes())throw new FileFailure(503,"ASSET_IDENTITY_MISMATCH");
                    var checked=new AuthorizedAssetStream(content.stream(),file.sizeBytes(),file.sha256(),()->{if(recovery)repository.observeProjection(command);else repository.guard(command);});
                    byte[] buffer=new byte[65536];while(checked.read(buffer)!=-1){/* verify immutable restored bytes */}
                }
            }
            return;
        }
        String key,hash;long bytes;
        if("APPLIED".equals(receipt.resultState())||"MUTATED".equals(receipt.resultState())&&"RESTORE_FILE".equals(command.operation().kind())) {key=command.upload().objectKey();hash=command.upload().sha256();bytes=command.upload().sizeBytes();}
        else if("CAPTURED".equals(receipt.resultState())) {
            var target=repository.captureTarget(command);var descriptor=repository.captureDescriptor(command);
            key=target.objectKey();hash=descriptor.sha256();bytes=descriptor.bytes();
        }else return;
        try(var content=assets.open(key,null,null)) {
            if(content.bytes()!=bytes||content.totalBytes()!=bytes)throw new FileFailure(503,"ASSET_IDENTITY_MISMATCH");
            var checked=new AuthorizedAssetStream(content.stream(),bytes,hash,()->{if(recovery)repository.observeProjection(command);else repository.guard(command);});
            byte[] buffer=new byte[65536];while(checked.read(buffer)!=-1){/* bounded integrity verification */}
        }
    }
}
