package com.agentplatform.domain.chat;

import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.types.harness.HarnessData.Call;
import java.util.List;
import java.util.UUID;

/** Immutable server intent. Physical jobs are separate authorities linked to the original operation. */
public interface WorkspaceToolLedger {
    record Intent(UUID operationId,UUID spaceId,UUID taskId,UUID workspaceId,String toolName,String argsHash,
                  long contentRevision,long permissionVersion,String resourceHash,String resourceJson,String reviewJson,
                  UUID activationRequestId,Long expectedEnvironmentVersion,String imageId,String policyHash) {}
    record FileAdmission(UUID operationId,UUID uploadId,UUID fileOperationId,String failureCode) {}
    enum StopReason { CANCEL, TIMEOUT, AUTHORIZATION, OWNER_LOST }
    default boolean workspaceToolTask(Lease lease){return false;}
    default void prepareWorkspaceTools(Lease lease,List<Call> calls,String imageId,String policyHash){
        throw new ChatFailure(422,"WORKSPACE_TOOLS_NOT_AVAILABLE");
    }
    default Intent workspaceToolIntent(Lease lease,Call call){throw new ChatFailure(422,"WORKSPACE_TOOLS_NOT_AVAILABLE");}
    default FileAdmission workspaceFileAdmission(Lease lease,Call call){throw new ChatFailure(422,"WORKSPACE_TOOLS_NOT_AVAILABLE");}
    default FileAdmission recordWorkspaceFileAdmission(Lease lease,Call call,UUID upload,String failure){throw new ChatFailure(422,"WORKSPACE_TOOLS_NOT_AVAILABLE");}
    default void bindWorkspaceFileOperation(Lease lease,Call call,UUID fileOperation){throw new ChatFailure(422,"WORKSPACE_TOOLS_NOT_AVAILABLE");}
    default boolean workspaceFileStopped(Lease lease,Call call){return false;}
    /** Stops admission of new effects; this request is never itself termination proof. */
    default void requestWorkspaceToolStop(Lease lease,Call call,StopReason reason){throw new ChatFailure(422,"WORKSPACE_TOOLS_NOT_AVAILABLE");}
    default com.agentplatform.domain.workspace.WorkspaceShellData.Request workspaceShellRequest(Lease lease,Call call){throw new ChatFailure(422,"WORKSPACE_TOOLS_NOT_AVAILABLE");}
}
