package com.agentplatform.types;

import java.util.List;

/** Public authorization metadata; never contains paths on the host or SDK permission rules. */
public final class TaskPermissionData {
    private TaskPermissionData() {}
    public enum Mode { RISK_BASED, FULL }
    public enum Capability { LIST, READ, WRITE }
    public record Grant(String id, String scopeId, String path, List<Capability> capabilities,
                        String expiresAt, String revokedAt, boolean active, String policyVersion) {}
    public record View(String taskId, String workspaceId, Mode mode, long version, String policyVersion,
                       boolean mutable, List<String> effectiveRestrictions, List<Grant> grants) {}
    public record Scope(String scopeId, String path, List<Capability> capabilities) {}
    public record Scopes(List<Scope> items, String nextCursor) {
        public Scopes(List<Scope> items) { this(items, null); }
    }
    public record Patch(Mode mode) {}
    public record CreateGrant(String scopeId, List<Capability> capabilities) {}
}
