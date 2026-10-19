package com.agentplatform.infrastructure.dao.po;
import java.util.UUID;
import java.time.Instant;
public final class LedgerRows {
    private LedgerRows() {}
    public record CheckpointRow1(Boolean value1) {}
    public record ModelTurnRow23(String value1) {}
    public record CheckpointRow172(UUID value1) {}
    public record CheckpointRow173(String manifestJson, String bundleHash, String stateJson, String canonicalJson, Long watermark, String canonicalHash, String stateHash, String manifestHash) {}
    public record ToolOperationRow175(String value1) {}
    public record ModelTurnRow176(String value2, String value3, UUID value1) {}
    public record ToolOperationRow177(String value1, String value2, String value3) {}
    public record ModelTurnRow178(String value2, String value4, String value5, String value6, Object value1) {}
    public record StateSlotRow179(String value1) {}
    public record StateSlotRow180(Long value1) {}
    public record StateSlotRow181(Long value1) {}
    public record StateSlotRow182(String value3, Long value1, String value2) {}
    public record ToolOperationRow183(String value1, String value2) {}
    public record ModelTurnRow185(String value1, Long value3, String value2, UUID value4) {}
    public record ModelTurnRow187(UUID value1) {}
}
