package com.agentplatform.api.capability;

import com.agentplatform.api.workspace.RunnerControlApi;

/** Signed internal runner route only. */
public interface CapabilityMaterializationRunnerApi {
    RunnerControlApi.SignedResult materialize(String token, byte[] body);
    RunnerControlApi.SignedResult query(String token, String operationId);
}
