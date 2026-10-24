package com.agentplatform.infrastructure.dao.po;

public final class FileRows {
    private FileRows() {}
    public record Storage(long objectLimitBytes, long reservedBytes, long committedBytes, long version, String status) {}
}
