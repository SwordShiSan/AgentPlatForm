package com.agentplatform.api.workspace;

import java.util.List;

/** Public projection deliberately excludes physical/container identities and execution inputs. */
public interface WorkspaceEnvironmentApi {
    record View(String workspaceId,String state,String phase,long generation,long version,String operationId,String errorCode,List<String> allowedActions) {}
    record Submission(String operationId,View environment) {}
    View get(WorkspaceApi.Caller caller,String workspaceId);
    Submission prepare(WorkspaceApi.Caller caller,String workspaceId,String requestId,long expectedVersion,boolean retry);
}
