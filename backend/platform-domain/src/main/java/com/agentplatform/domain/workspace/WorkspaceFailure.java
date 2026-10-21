package com.agentplatform.domain.workspace;

public final class WorkspaceFailure extends RuntimeException {
    private final int status;
    private final String code;
    public WorkspaceFailure(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
}
