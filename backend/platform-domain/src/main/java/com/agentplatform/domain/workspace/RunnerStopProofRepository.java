package com.agentplatform.domain.workspace;

/** Protected offline operator port. Recording host evidence does not release a sandbox slot. */
public interface RunnerStopProofRepository {
    void record(StoppedRunner proof);
}
