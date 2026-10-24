package com.agentplatform.domain.files;

public final class FileFailure extends RuntimeException {
    private final int status;
    public FileFailure(int status, String code) { super(code); this.status = status; }
    public int status() { return status; }
}
