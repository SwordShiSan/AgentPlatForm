package com.agentplatform.domain.workspace;

import java.util.Objects;
import java.util.UUID;

/** New shell wire family. It never adds fields to the legacy file/probe request. */
public final class WorkspaceShellData {
    public static final String PROTOCOL="WORKSPACE_SHELL_V1";
    private WorkspaceShellData() {}
    public record Parent(UUID operationId,UUID taskId,UUID attemptId,UUID ownerId,long taskGeneration,long taskFence,
                         String argumentsHash,long contentRevision,long permissionVersion) {
        public Parent {
            Objects.requireNonNull(operationId);Objects.requireNonNull(taskId);Objects.requireNonNull(attemptId);Objects.requireNonNull(ownerId);
            if(taskGeneration<1||taskFence<1||contentRevision<0||permissionVersion<1||argumentsHash==null||!argumentsHash.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("INVALID_SHELL_PARENT_BINDING");
        }
    }
    public record Request(String protocol,Parent parent, SandboxRuntime.ShellRequest shell) implements EnvironmentUseData.UseRequest {
        public Request {
            if(!PROTOCOL.equals(protocol)||parent==null||shell==null)throw new IllegalArgumentException("INVALID_SHELL_REQUEST");
        }
        @Override public String kind(){return "SHELL";}
    }
}
