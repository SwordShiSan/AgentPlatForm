package com.agentplatform.domain.workspace;

/** Host-driven acceptance seam; production composition does nothing. Never user-controlled. */
@FunctionalInterface
public interface WorkspaceEffectBoundary {
    WorkspaceEffectBoundary NONE=point->{};
    void await(String point);
}
