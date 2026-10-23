package com.agentplatform.domain.workspace;

import java.time.Instant;
import java.util.UUID;

public final class EnvironmentUseData {
    private EnvironmentUseData() {}
    public record Request(SandboxRuntime.FileRequest file,SandboxRuntime.Probe probe) {
        public Request {
            if((file==null)==(probe==null))throw new WorkspaceFailure(400,"INVALID_WORKSPACE_USE");
            if(file!=null&&(file.action()==null||file.path()==null))throw new WorkspaceFailure(400,"INVALID_WORKSPACE_USE");
        }
        public String kind() {return file==null?"FIXED_PROBE":"FILE_"+file.action().name();}
    }
    public record Command(UUID id,UUID spaceId,UUID workspaceId,UUID environmentId,UUID actorId,UUID ownerId,
            long generation,long fence,UUID creationCommandId,long creationFence,int projectId,String imageId,String policyHash,
            String containerId,String containerStartedAt,String kind,String requestHash,Request request,Instant executeUntil,Instant deadline) {
        public SandboxRuntime.Identity physicalIdentity() {return new SandboxRuntime.Identity(environmentId,spaceId,workspaceId,creationCommandId,generation,creationFence,projectId);}
    }
    public record Admission(Command command,boolean fresh) {}
    /** Raw bounded payload is stored privately, never sent in the signed management receipt. */
    public record Fact(UUID useId,UUID environmentId,long generation,long fence,String requestHash,UUID runnerInstanceId,
            String resultState,SandboxRuntime.Output output,SandboxRuntime.Stop stop,String errorCode) {}
    public record Receipt(UUID useId,String state,String resultState,String resultHash) {}
}
