package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.infrastructure.dao.chat.ChatMappers;
import com.agentplatform.infrastructure.dao.po.PermissionRows.*;
import com.agentplatform.types.TaskPermissionData.*;
import com.agentplatform.types.Uuid7;
import com.agentplatform.types.RequestTrace;
import com.agentplatform.types.harness.HarnessData;
import java.util.*;

/** Scoped authorization facts share the same Spring transaction and lock ordering as task ownership. */
final class PermissionPersistence extends ChatPersistenceSupport {
    PermissionPersistence(ChatMappers mappers) { super(mappers); }
    private static boolean mutable(PermissionRow p) {
        return !p.cancelRequested() && Set.of("QUEUED", "RUNNING", "WAITING_INPUT").contains(p.status());
    }
    private static PermissionRow read(ChatMappers c, Actor a, UUID task) {
        auth(c, a);
        var p = c.permissions().permission(a.spaceId(), task);
        if (p == null || !a.userId().equals(p.actorId())) throw new ChatFailure(404, "RESOURCE_NOT_FOUND");
        return p;
    }
    private static boolean boundAuthorized(ChatMappers c, PermissionRow p) {
        return p.workspaceId() != null && c.permissions().workspace(new Actor(p.actorId(), p.spaceId()), p.workspaceId());
    }
    private static String directory(ChatMappers c, PermissionRow p, UUID scope) {
        if (!boundAuthorized(c, p)) throw new ChatFailure(409, "WORKSPACE_SCOPE_UNAVAILABLE");
        if (scope.equals(p.rootScopeId())) return "";
        var row = c.permissions().directory(p.spaceId(), p.workspaceId(), scope);
        if (row == null) throw new ChatFailure(404, "PERMISSION_SCOPE_NOT_FOUND");
        try {
            String canonical = RiskPolicy.path(row.path());
            if (!canonical.equals(row.path())) throw new IllegalArgumentException("NON_CANONICAL_INDEX");
            return canonical;
        }
        catch (IllegalArgumentException invalid) { throw new ChatFailure(409, "PERMISSION_SCOPE_CHANGED"); }
    }
    @SuppressWarnings("unchecked")
    private static List<Capability> caps(String json) {
        return ((List<String>) JSON.readValue(json, List.class)).stream().map(Capability::valueOf).toList();
    }
    private static boolean active(ChatMappers c, PermissionRow p, GrantRow g) {
        if (!mutable(p) || g.revokedAt() != null || !g.expiresAt().isAfter(p.now())
                || !RiskPolicy.VERSION.equals(p.policyVersion()) || !p.policyVersion().equals(g.policyVersion())) return false;
        try { return directory(c, p, g.scopeId()).equals(g.path()); }
        catch (ChatFailure invalid) { return false; }
    }
    private static View view(ChatMappers c, PermissionRow p) {
        var grants = c.permissions().grants(p.spaceId(), p.taskId()).stream().map(g -> new Grant(g.id().toString(), g.scopeId().toString(), g.path(), caps(g.capabilities()),
                g.expiresAt().toString(), g.revokedAt() == null ? null : g.revokedAt().toString(), active(c, p, g), g.policyVersion())).toList();
        return new View(p.taskId().toString(), p.workspaceId() == null ? null : p.workspaceId().toString(), Mode.valueOf(p.mode()), p.version(), RiskPolicy.VERSION, mutable(p),
                List.of("REALTIME_RESOURCE_ACL", "PLAN_TEXT_ONLY", "MANDATORY_POLICIES", "BUSINESS_TOOLS_NOT_ENABLED", "TASK_SCOPED_24H_GRANTS"), grants);
    }
    View permissions(Actor a, UUID task) { return tx(c -> view(c, read(c, a, task))); }
    Scopes scopes(Actor a, UUID task) { return scopes(a,task,null); }
    Scopes scopes(Actor a, UUID task, String cursor) { return tx(c -> {
        var p = read(c, a, task);
        if (!mutable(p) || !boundAuthorized(c, p)) return new Scopes(List.of());
        var result = new ArrayList<Scope>();var capabilities = List.of(Capability.LIST, Capability.READ, Capability.WRITE);
        String after=null;
        if(cursor!=null){
            UUID id;try{id=UUID.fromString(cursor);}catch(IllegalArgumentException invalid){throw new ChatFailure(400,"INVALID_CURSOR");}
            var prior=c.permissions().directory(a.spaceId(),p.workspaceId(),id);
            if(prior==null)throw new ChatFailure(409,"PERMISSION_SCOPE_CURSOR_CHANGED");
            after=prior.path();
        } else result.add(new Scope(p.rootScopeId().toString(), "", capabilities));
        var directories=c.permissions().directories(a.spaceId(),p.workspaceId(),after);
        for (var d : directories.stream().limit(50).toList()) {
            try {
                String canonical = RiskPolicy.path(d.path());
                if (canonical.equals(d.path())) result.add(new Scope(d.id().toString(), canonical, capabilities));
            }
            catch (IllegalArgumentException invalid) { /* An invalid index entry never becomes an authorization scope. */ }
        }
        return new Scopes(List.copyOf(result),directories.size()>50?directories.get(49).id().toString():null);
    }); }
    static void bind(ChatMappers c, Actor a, UUID task, UUID workspace, Mode mode) {
        if (workspace != null && !c.permissions().workspace(a, workspace)) throw new ChatFailure(404, "WORKSPACE_NOT_FOUND");
        c.permissions().bind(a, task, workspace, Uuid7.next(), mode.name());
        c.permissions().initialize(a.spaceId(), task, mode.name());
    }
    private static PermissionRow lock(ChatMappers c, Actor a, UUID id) {
        var p = read(c, a, id);
        new MyBatisQuotaLedger(c.quota()).lock(a.userId());
        c.dispatch().updateExecutionSlot87();
        conversation(c, a, p.conversationId(), true);task(c, a, id, true);
        return read(c, a, id);
    }
    View mode(Actor a, UUID task, UUID key, long expected, Mode mode) {
        if (mode == null) throw new ChatFailure(400, "PERMISSION_MODE_REQUIRED");
        return change(a, task, key, expected, hash("MODE\n" + mode + "\n" + expected), "MODE_CHANGED", (c, p) -> {
            if (mode.name().equals(p.mode())) return false;
            if (c.permissions().mode(task, mode.name(), expected) != 1) throw new ChatFailure(412, "PERMISSION_VERSION_CONFLICT");
            return true;
        });
    }
    View grant(Actor a, UUID task, UUID key, long expected, CreateGrant request) {
        if (request == null || request.scopeId() == null || request.capabilities() == null || request.capabilities().isEmpty()
                || request.capabilities().size() > 3 || request.capabilities().stream().anyMatch(Objects::isNull)
                || new HashSet<>(request.capabilities()).size() != request.capabilities().size()) throw new ChatFailure(400, "INVALID_DIRECTORY_GRANT");
        UUID scope;try { scope = UUID.fromString(request.scopeId()); } catch (IllegalArgumentException invalid) { throw new ChatFailure(400, "INVALID_UUID"); }
        var capabilities = request.capabilities().stream().sorted().toList();
        return change(a, task, key, expected, hash("GRANT\n" + scope + "\n" + capabilities + "\n" + expected), "GRANT_CREATED", (c, p) -> {
            String path = directory(c, p, scope);
            var grants = c.permissions().grants(a.spaceId(), task);
            if (grants.stream().anyMatch(g -> g.scopeId().equals(scope) && caps(g.capabilities()).equals(capabilities) && active(c, p, g))) return false;
            if (grants.size() >= 1000) throw new ChatFailure(409, "TASK_GRANT_LIMIT");
            c.permissions().insertGrant(Uuid7.next(), p, scope, path, json(capabilities), RiskPolicy.VERSION);
            if (c.permissions().advance(task, expected) != 1) throw new ChatFailure(412, "PERMISSION_VERSION_CONFLICT");
            return true;
        });
    }
    View revoke(Actor a, UUID task, UUID grant, UUID key, long expected) {
        return change(a, task, key, expected, hash("REVOKE\n" + grant + "\n" + expected), "GRANT_REVOKED", (c, p) -> {
            var prior = c.permissions().grants(a.spaceId(), task).stream().filter(g -> g.id().equals(grant)).findFirst().orElseThrow(() -> new ChatFailure(404, "GRANT_NOT_FOUND"));
            if (prior.revokedAt() != null) return false;
            c.permissions().revoke(a.spaceId(), task, grant);
            if (c.permissions().advance(task, expected) != 1) throw new ChatFailure(412, "PERMISSION_VERSION_CONFLICT");
            return true;
        });
    }
    private interface Mutation { boolean apply(ChatMappers c, PermissionRow p); }
    private View change(Actor a, UUID task, UUID key, long expected, String digest, String kind, Mutation mutation) {
        return tx(c -> {
            var p = lock(c, a, task);
            var old = c.permissions().command(a, task, key);
            if (old != null) {
                if (!old.requestHash().equals(digest)) throw new ChatFailure(409, "IDEMPOTENCY_CONFLICT");
                return JSON.readValue(old.responseJson(), View.class);
            }
            if (expected != p.version()) throw new ChatFailure(412, "PERMISSION_VERSION_CONFLICT");
            if (!mutable(p)) throw new ChatFailure(409, "TASK_PERMISSIONS_READ_ONLY");
            if (!RiskPolicy.VERSION.equals(p.policyVersion())) throw new ChatFailure(409, "PERMISSION_POLICY_CHANGED");
            c.permissions().initialize(a.spaceId(), task, p.mode());
            boolean changed = mutation.apply(c, p);
            var result = view(c, read(c, a, task));
            if (changed) {
                var now = read(c, a, task);
                c.permissions().audit(Uuid7.next(), now, null, null, null, kind, kind, digest, RequestTrace.current());
                event(c, a, p.conversationId(), task, "task.permissions_changed", result.version(), Map.of("taskId", task.toString(), "permissionVersion", result.version(), "mode", result.mode().name(), "change", kind));
            }
            c.permissions().remember(a, task, key, digest, json(result));
            return result;
        });
    }
    static HarnessData.Decision decision(ChatMappers c, Lease lease, HarnessData.Call call, boolean audit) {
        MyBatisExecutionLedger.guard(c, lease);
        var p = read(c, new Actor(lease.userId(), lease.spaceId()), lease.taskId());
        HarnessData.Decision result;
        String reason;
        if (!RiskPolicy.VERSION.equals(p.policyVersion())) {
            result = HarnessData.Decision.DENY;reason = "PERMISSION_POLICY_CHANGED";
        } else if (PlanControls.NAMES.contains(call.name())) {
            result = new PlanPersistence(c).controlDecision(lease, call);reason = "PLATFORM_CONTROL";
        } else if(WorkspaceTools.NAMES.contains(call.name())) {
            var policy=WorkspaceToolPersistence.decision(c,lease,call);result=policy.decision();reason=policy.reasonCode();
        } else {
            // Only the fixed deterministic probe can be supplied by the protected fixture composition.
            // Production has no business definitions. Neither FULL nor a grant registers a capability.
            boolean approvalFixture = "approval_probe".equals(call.name()) && call.arguments().keySet().equals(Set.of("text"))
                    && call.arguments().get("text") instanceof String text && !text.isBlank() && text.length()<=256 && text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=4096;
            boolean fixture = approvalFixture || "recovery_probe".equals(call.name()) && call.arguments().keySet().equals(Set.of("text"));
            Capability fileCapability = switch (call.name()) {
                case "workspace_file_list" -> Capability.LIST;
                case "workspace_file_read" -> Capability.READ;
                case "workspace_file_write" -> Capability.WRITE;
                default -> null;
            };
            boolean authorized = fixture || boundAuthorized(c, p), granted = false;
            if (fileCapability != null && authorized && call.arguments().get("path") instanceof String target) {
                try {
                    String normalized = RiskPolicy.path(target);
                    granted = c.permissions().grants(p.spaceId(), p.taskId()).stream().anyMatch(g -> active(c, p, g)
                            && caps(g.capabilities()).contains(fileCapability) && RiskPolicy.contains(g.path(), normalized));
                } catch (IllegalArgumentException invalid) { authorized = false; }
            }
            var policy = new RiskPolicy().decide(new RiskPolicy.Facts(authorized, p.planMode(), fixture, true,
                    approvalFixture?RiskPolicy.Mandatory.ASK:RiskPolicy.Mandatory.NONE, Mode.valueOf(p.mode()), granted, fixture&&!approvalFixture));
            result = policy.decision();reason = policy.reasonCode();
        }
        var effective=ApprovalPersistence.apply(c,lease,call,result);
        if(effective!=result)reason=effective==HarnessData.Decision.ALLOW?"EXACT_APPROVAL_ONCE":"APPROVAL_DENIED_OR_STALE";
        result=effective;
        if (audit) c.permissions().audit(Uuid7.next(), p, lease.attemptId(), c.permissions().operation(lease.taskId(), lease.attemptId(), call.id()), call.id(), result.name(), reason,
                hash(MyBatisExecutionLedger.canonicalJson(call)), RequestTrace.current());
        return result;
    }
}
