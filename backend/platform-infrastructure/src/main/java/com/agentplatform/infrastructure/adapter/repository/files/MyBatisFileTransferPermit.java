package com.agentplatform.infrastructure.adapter.repository.files;

import com.agentplatform.domain.files.*;
import com.agentplatform.infrastructure.dao.files.FileTransferMapper;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class MyBatisFileTransferPermit implements FileTransferPermit {
    private final FileTransferMapper mapper;
    private final String role;
    private final UUID process;
    public MyBatisFileTransferPermit(FileTransferMapper mapper){this(mapper,"TEST",UUID.randomUUID());}
    public MyBatisFileTransferPermit(FileTransferMapper mapper,String role,UUID process){this.mapper=mapper;this.role=role;this.process=process;}
    private static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Transfer admission requires short transaction");}
    @Override public UUID acquire(String key) {
        transaction();var owner=UUID.randomUUID();
        if(mapper.acquire(owner,RunnerSignatures.sha256(key.getBytes(StandardCharsets.UTF_8)),role,process)!=1)throw new FileFailure(423,"FILE_TRANSFER_BUSY");
        return owner;
    }
    @Override public void release(UUID owner){transaction();if(!mapper.released(owner)&&mapper.release(owner)!=1&&!mapper.released(owner))throw new FileFailure(409,"FILE_TRANSFER_OWNER_MISMATCH");}
}
