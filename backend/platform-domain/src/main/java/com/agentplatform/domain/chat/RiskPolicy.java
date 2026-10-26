package com.agentplatform.domain.chat;

import com.agentplatform.types.TaskPermissionData.Mode;
import com.agentplatform.types.harness.HarnessData.Decision;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;

/** Deterministic policy. Trusted adapters supply facts; model readOnly claims are not facts. */
public final class RiskPolicy {
    public static final String VERSION = "task-directory-policy-v1";
    public enum Action { LIST, READ, WRITE, DELETE, EXECUTE, NETWORK }
    public enum Mandatory { NONE, ASK, DENY }
    public record Facts(boolean authorized, boolean planMode, boolean available, boolean environmentSafe,
                        Mandatory mandatory, Mode mode, boolean granted, boolean lowRisk) {}
    public record Operation(Action action, boolean targetCurrent, boolean versionBacked, int targetCount, boolean external) {}
    public record Result(Decision decision, String reasonCode, String policyVersion) {}
    public Result decide(Facts f) {
        if (!f.authorized()) return result(Decision.DENY, "RESOURCE_NOT_AUTHORIZED");
        if (f.planMode()) return result(Decision.DENY, "PLAN_TEXT_ONLY");
        if (!f.available()) return result(Decision.DENY, "CAPABILITY_NOT_AVAILABLE");
        if (!f.environmentSafe()) return result(Decision.DENY, "ENVIRONMENT_BOUNDARY");
        if (f.mandatory() == Mandatory.DENY) return result(Decision.DENY, "MANDATORY_DENY");
        if (f.mandatory() == Mandatory.ASK) return result(Decision.ASK, "MANDATORY_ASK");
        if (f.mode() == Mode.FULL) return result(Decision.ALLOW, "TASK_FULL");
        if (f.granted()) return result(Decision.ALLOW, "DIRECTORY_GRANT");
        return result(f.lowRisk() ? Decision.ALLOW : Decision.ASK, f.lowRisk() ? "LOW_RISK" : "CONFIRMATION_REQUIRED");
    }
    public Result decide(Facts f, Operation operation) {
        if (!operation.targetCurrent()) return result(Decision.DENY, "TARGET_VERSION_CHANGED");
        boolean low = !operation.external() && switch (operation.action()) {
            case LIST, READ -> true;
            case WRITE -> operation.versionBacked();
            case DELETE, EXECUTE, NETWORK -> false;
        };
        // A directory grant only applies to constrained file capabilities, never arbitrary execution/exfiltration.
        boolean grant = f.granted() && !operation.external()
                && (operation.action()==Action.LIST || operation.action()==Action.READ || operation.action()==Action.WRITE);
        return decide(new Facts(f.authorized(), f.planMode(), f.available(), f.environmentSafe(), f.mandatory(), f.mode(), grant, low));
    }
    private static Result result(Decision decision, String reason) { return new Result(decision, reason, VERSION); }
    public static String path(String value) {
        if (value == null) throw new IllegalArgumentException("INVALID_SCOPE_PATH");
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        if (normalized.isEmpty()) return ""; // The workspace root is represented without a host path.
        if (normalized.getBytes(StandardCharsets.UTF_8).length > 4096 || normalized.startsWith("/")
                || normalized.contains("\\") || normalized.indexOf(':') >= 0
                || normalized.codePoints().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("INVALID_SCOPE_PATH");
        for (String segment : normalized.split("/", -1))
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException("INVALID_SCOPE_PATH");
        return normalized;
    }
    public static boolean contains(String directory, String target) {
        String base = path(directory), file = path(target);
        return base.isEmpty() || file.equals(base) || file.startsWith(base + "/");
    }
}
