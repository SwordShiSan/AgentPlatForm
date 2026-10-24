package com.agentplatform.domain.files;

import java.util.UUID;

/** Global bounded transport admission. Expiry alone must never admit another physical transfer. */
public interface FileTransferPermit {
    UUID acquire(String objectKey);
    void release(UUID owner);
}
