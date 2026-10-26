package com.agentplatform.infrastructure.chat;

import com.agentplatform.domain.chat.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuotaPolicyTest {
    @Test void calendarWindowsAreHalfOpenAndTimezoneAware() {
        var policy=new QuotaData.Policy(UUID.randomUUID(),QuotaData.Metric.TOKENS,1000000L,"Asia/Shanghai",1);
        var before=policy.window(Instant.parse("2026-10-26T15:59:59Z"));
        var after=policy.window(Instant.parse("2026-10-26T16:00:00Z"));
        assertEquals(before.end(),after.start());assertEquals(Instant.parse("2026-10-26T16:00:00Z"),before.end());
        var dst=new QuotaData.Policy(UUID.randomUUID(),QuotaData.Metric.TOKENS,1L,"America/New_York",1).window(Instant.parse("2026-03-08T12:00:00Z"));
        assertEquals(23,Duration.between(dst.start(),dst.end()).toHours());
    }
    @Test void ordinaryQuotaCountsReservedAndConsumedWhileAdministratorsRemainMeteredUnlimited() {
        assertDoesNotThrow(()->QuotaData.admit(1000000L,false,990000,784,9216));
        assertEquals("DAILY_TOKEN_QUOTA_EXCEEDED",assertThrows(ChatFailure.class,()->QuotaData.admit(1000000L,false,990000,785,9216)).code());
        assertDoesNotThrow(()->QuotaData.admit(1000000L,true,2000000,9216,9216));
        assertDoesNotThrow(()->QuotaData.admit(null,false,2000000,9216,9216));
        assertThrows(ChatFailure.class,()->QuotaData.admit(null,true,Long.MAX_VALUE,1,1));
    }
}
