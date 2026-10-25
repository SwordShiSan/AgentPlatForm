package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.EnvironmentExecutionRepository;
import com.agentplatform.domain.workspace.EnvironmentRunnerPort;
import com.agentplatform.domain.workspace.WorkspaceEffectBoundary;
import java.util.Set;
import java.util.UUID;

/** Existing maintenance calls this bounded GET-only observer, never a new execution owner. */
public final class RunnerReconciler {
    private final EnvironmentExecutionRepository repository;
    private final EnvironmentRunnerPort runner;
    private final WorkspaceEffectBoundary boundary;
    public RunnerReconciler(EnvironmentExecutionRepository repository,EnvironmentRunnerPort runner) {
        this(repository,runner,WorkspaceEffectBoundary.NONE);
    }
    public RunnerReconciler(EnvironmentExecutionRepository repository,EnvironmentRunnerPort runner,WorkspaceEffectBoundary boundary) {
        this.repository=repository;this.runner=runner;this.boundary=boundary;
    }
    public void reconcile(UUID observerId) {
        for(var observation:repository.observations(observerId)) {
            try {
                var receipt=runner.queryEnvironment(observation.command());
                if(receipt!=null&&Set.of("COMMITTED","FAILED").contains(receipt.state())) {
                    boundary.await("environment-observation-before-project");
                    repository.completeObserved(observation,receipt);
                    boundary.await("environment-observation-after-project");
                }
            } catch(RuntimeException unconfirmed) {
                // Retain the original command, capacity and observation history.
                // No prepare/stop POST, execution lease renewal or alternate owner.
            }
        }
    }
}
