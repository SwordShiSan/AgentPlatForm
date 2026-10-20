package com.agentplatform.types;

/** Shared stable categories; never records exception bodies, keys or conversation text. */
public final class FailureRecorder {
    private FailureRecorder() {}
    public static String classification(String code, boolean unknown) {
        if (unknown || "EXTERNAL_RESULT_UNKNOWN".equals(code) || "EXECUTION_STOP_UNCONFIRMED".equals(code)) return "UNKNOWN_SIDE_EFFECT";
        if (code==null) return "NONE";
        if (code.equals("TASK_CANCEL_REQUESTED")) return "CANCELLED";
        if (code.equals("EXECUTION_INTERRUPTED_RECONCILED")) return "NONE";
        if (code.contains("CREDENTIAL") || code.contains("AUTH") || code.contains("ORIGIN") || code.contains("CSRF") || code.contains("FENCE") || code.equals("RESOURCE_NOT_FOUND")) return "POLICY_DENIED";
        if (code.contains("QUOTA") || code.contains("BALANCE") || code.contains("CONTEXT") || code.contains("CAPABILITY")) return "USER_ACTION";
        if (code.equals("PROVIDER_RATE_LIMITED") || code.equals("PROVIDER_SERVER_REJECTED") || code.contains("STORAGE_UNAVAILABLE") || code.contains("PUBLISH")) return "TRANSIENT_SAFE_RETRY";
        return "BUG";
    }
    public static String action(String category) {
        return switch(category) {
            case "UNKNOWN_SIDE_EFFECT" -> "CONTACT_OPERATOR_FOR_RECONCILIATION";
            case "POLICY_DENIED" -> "CHECK_ACCESS_AND_CREDENTIAL";
            case "USER_ACTION" -> "CHECK_CONFIGURATION_AND_BUDGET";
            case "TRANSIENT_SAFE_RETRY" -> "CHECK_SERVICE_STATUS";
            case "NONE", "CANCELLED" -> "NONE";
            default -> "CONTACT_OPERATOR";
        };
    }
    public static void request(String code, java.util.UUID trace) {
        System.getLogger(FailureRecorder.class.getName()).log(System.Logger.Level.WARNING,
            "requestFailure code="+code+" traceId="+trace+" classification="+classification(code,false));
    }
}
