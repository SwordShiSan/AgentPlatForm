package com.agentplatform.domain.chat;

import java.time.*;
import java.util.UUID;

/** Common quota values. Calendar policy is separate from execution time and context limits. */
public final class QuotaData {
    private QuotaData() {}
    public enum Metric { TOKENS, COST_MICROS }
    public record Policy(UUID id, Metric metric, Long limit, String timezone, long version) {
        public Policy {
            if(id==null||metric==null||version<1||(limit!=null&&limit<0))throw new IllegalArgumentException("QUOTA_POLICY_INVALID");
            ZoneId.of(timezone);
        }
        public Window window(Instant now) {
            var zone=ZoneId.of(timezone);var date=now.atZone(zone).toLocalDate();
            return new Window(date.atStartOfDay(zone).toInstant(),date.plusDays(1).atStartOfDay(zone).toInstant());
        }
    }
    public record Window(Instant start, Instant end) {}
    public record View(Metric metric, boolean unlimited, Long limit, long used, long reserved,
                       Long remaining, Instant resetAt, long policyVersion) {}
    public record Change(UUID commandId,UUID userId,Metric metric,Long limit,String timezone,long expectedVersion,String operator) {
        public Change {
            if(commandId==null||metric==null||expectedVersion<0||limit!=null&&limit<0||operator==null||!operator.matches("[a-zA-Z0-9_.@-]{1,128}"))
                throw new IllegalArgumentException("QUOTA_POLICY_INVALID");
            ZoneId.of(timezone);
        }
    }
    public static void admit(Long limit, boolean administrator, long used, long reserved, long requested) {
        if(used<0||reserved<0||requested<0)throw new IllegalArgumentException("QUOTA_ACCOUNTING_INVALID");
        try {
            long total=Math.addExact(Math.addExact(used,reserved),requested);
            if(!administrator&&limit!=null&&total>limit)throw new ChatFailure(429,"DAILY_TOKEN_QUOTA_EXCEEDED");
        } catch(ArithmeticException overflow) { throw new ChatFailure(429,"QUOTA_ACCOUNTING_OVERFLOW"); }
    }
}
