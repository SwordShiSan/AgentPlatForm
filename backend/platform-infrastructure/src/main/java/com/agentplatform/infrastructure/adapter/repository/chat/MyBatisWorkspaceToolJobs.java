package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.infrastructure.dao.chat.WorkspaceToolJobMapper;
import com.agentplatform.types.Uuid7;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class MyBatisWorkspaceToolJobs implements WorkspaceToolJobs {
    private final WorkspaceToolJobMapper mapper;
    public MyBatisWorkspaceToolJobs(WorkspaceToolJobMapper mapper){this.mapper=mapper;}
    private static void tx(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Workspace tool transaction required");}
    public void lockQuota(UUID user,UUID space){tx();if(mapper.lockPrincipal(user)==null||mapper.lockStorage(space)==null)throw new ChatFailure(409,"OBJECT_QUOTA_UNAVAILABLE");}
    public Environment environment(UUID space,UUID workspace){tx();return mapper.environment(space,workspace);}
    public void bindActivation(UUID operation,UUID activation){tx();if(mapper.bindActivation(operation,activation,Uuid7.next())!=1&&!mapper.activationBound(operation,activation))throw new ChatFailure(409,"TOOL_ACTIVATION_CONFLICT");}
    public void observeStoppedSource(UUID operation,UUID use){tx();if(!mapper.observeStoppedSource(operation,use))throw new ChatFailure(409,"TOOL_STOP_UNCONFIRMED");}
    public List<Artifact> artifacts(UUID operation){tx();return mapper.artifacts(operation);}
    public void settleResult(UUID operation,String key,String hash,int stdout,int stderr){tx();if(!mapper.settleResult(operation,key,hash,stdout,stderr))throw new ChatFailure(409,"TOOL_RESULT_SETTLEMENT_CONFLICT");}
    public boolean childrenStopped(UUID operation){tx();return mapper.childrenStopped(operation);}
}
