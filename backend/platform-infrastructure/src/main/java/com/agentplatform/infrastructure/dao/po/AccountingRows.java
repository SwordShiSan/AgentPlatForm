package com.agentplatform.infrastructure.dao.po;
import java.util.UUID;
import java.time.Instant;
public final class AccountingRows {
    private AccountingRows() {}
    public record ModelCallRow5(Boolean value1) {}
    public record ModelCallRow24(Boolean value1, Boolean value2) {}
    public record BudgetReservationRow34(String value1) {}
    public record ModelCallRow35(Integer value1, Instant value2) {}
    public record ModelCallRow50(UUID id, String status, Long inputTokens, Long outputTokens, Long costMicros, Boolean estimated) {}
}
