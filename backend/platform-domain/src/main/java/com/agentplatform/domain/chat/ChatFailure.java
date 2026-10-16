package com.agentplatform.domain.chat;

public final class ChatFailure extends RuntimeException {
    private final int status;
    private final String code;
    public ChatFailure(int status, String code) { super(code); this.status=status; this.code=code; }
    public int status() { return status; }
    public String code() { return code; }
}
