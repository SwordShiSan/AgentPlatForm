package com.agentplatform.infrastructure.adapter.repository.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.infrastructure.dao.workspace.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class MyBatisRunnerStopProofRepository implements RunnerStopProofRepository {
    private final RunnerStopProofMapper mapper;
    private final EnvironmentPreparationMapper environments;
    public MyBatisRunnerStopProofRepository(RunnerStopProofMapper mapper,EnvironmentPreparationMapper environments) {
        this.mapper=mapper;this.environments=environments;
    }
    @Override public void record(StoppedRunner proof) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Runner proof transaction required");
        environments.lockSlot();
        var actual=mapper.lockRunner(proof.instance().id());
        if(!proof.instance().equals(actual))throw new WorkspaceFailure(409,"STOPPED_RUNNER_IDENTITY_MISMATCH");
        var previous=mapper.evidence(actual.id());
        if(previous!=null) {
            if(!previous.equals(proof.evidenceHash()))throw new WorkspaceFailure(409,"STOPPED_RUNNER_PROOF_CONFLICT");
            return;
        }
        if(!mapper.fresh(proof))throw new WorkspaceFailure(409,"STOPPED_RUNNER_PROOF_EXPIRED");
        mapper.insert(proof);
        // Deliberately no environment/task state change, replay, owner transfer or capacity release.
    }
}
