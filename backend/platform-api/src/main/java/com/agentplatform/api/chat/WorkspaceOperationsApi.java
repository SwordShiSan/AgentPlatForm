package com.agentplatform.api.chat;

import com.agentplatform.types.WorkspaceOperationData.Page;

public interface WorkspaceOperationsApi {
    Page list(ChatApi.Caller caller,String taskId,String cursor,int limit);
    com.agentplatform.types.WorkspaceOperationData.Result result(ChatApi.Caller caller,String operationId);
}
