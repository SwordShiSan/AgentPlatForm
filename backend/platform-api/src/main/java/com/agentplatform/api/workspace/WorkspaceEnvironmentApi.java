package com.agentplatform.api.workspace;

import java.util.List;

/** Public projection deliberately excludes physical/container identities and execution inputs. */
public interface WorkspaceEnvironmentApi {
    record View(String workspaceId,String state,String phase,long generation,long version,String operationId,String errorCode,List<String> allowedActions) {}
    record Submission(String operationId,View environment) {}
    enum Action { SLEEP, WAKE, DELETE }
    record OperationView(String operationId,String workspaceId,String intent,String status,long generation,long revision,String errorCode) {}
    View get(WorkspaceApi.Caller caller,String workspaceId);
    Submission prepare(WorkspaceApi.Caller caller,String workspaceId,String requestId,long expectedVersion,boolean retry);
    default Submission transition(WorkspaceApi.Caller caller,String workspaceId,String requestId,long expectedVersion,Action action) {
        throw new UnsupportedOperationException("Lifecycle commands are unavailable");
    }
    default OperationView operation(WorkspaceApi.Caller caller,String workspaceId,String operationId) {
        throw new UnsupportedOperationException("Lifecycle operation lookup is unavailable");
    }
    default OperationView request(WorkspaceApi.Caller caller,String workspaceId,String requestId) {
        throw new UnsupportedOperationException("Lifecycle request lookup is unavailable");
    }
}
