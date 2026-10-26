package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.QuotaData.*;
import com.agentplatform.infrastructure.dao.chat.QuotaMapper;
import com.agentplatform.infrastructure.dao.po.QuotaRows;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Common calendar quota admission/settlement; no model-call-count or username exceptions. */
public final class MyBatisQuotaLedger implements QuotaLedger {
    private final QuotaMapper mapper;
    public MyBatisQuotaLedger(QuotaMapper mapper) { this.mapper=Objects.requireNonNull(mapper); }
    private QuotaRows.Principal principal(UUID user,boolean requireActive) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Application transaction required");
        mapper.ensurePrincipal(user);
        var principal=mapper.lockPrincipal(user);
        if(principal==null||requireActive&&!principal.active())throw new ChatFailure(404,"RESOURCE_NOT_FOUND");
        return principal;
    }
    public void lock(UUID user) { principal(user,false); }
    private List<QuotaRows.Policy> policies(UUID user){
        var policies=mapper.policies(user);
        if(policies.size()!=2||policies.stream().map(QuotaRows.Policy::metric).distinct().count()!=2)
            throw new ChatFailure(503,"QUOTA_POLICY_UNAVAILABLE");
        return policies;
    }
    public void reserve(UUID user, UUID call, long tokens, long costMicros) {
        var principal=principal(user,true);
        if(!mapper.reservations(call).isEmpty())throw new ChatFailure(409,"CALL_RESERVATION_EXISTS");
        for(var row:policies(user)) {
            var policy=new Policy(row.id(),Metric.valueOf(row.metric()),row.limitValue(),row.timezone(),row.version());
            var window=policy.window(principal.now());
            mapper.ensureBucket(user,row.metric(),window.start(),window.end());
            var bucket=mapper.bucket(user,row.metric(),window.start());
            long amount=policy.metric()==Metric.TOKENS?tokens:costMicros;
            long pending=Math.addExact(bucket.reserved(),mapper.earlierOutstanding(user,row.metric(),window.start()));
            try { QuotaData.admit(policy.limit(),principal.administrator(),bucket.consumed(),pending,amount); }
            catch(ChatFailure rejected) {
                if(policy.metric()==Metric.COST_MICROS&&"DAILY_TOKEN_QUOTA_EXCEEDED".equals(rejected.code()))
                    throw new ChatFailure(429,"DAILY_COST_QUOTA_EXCEEDED");
                throw rejected;
            }
            mapper.insertReservation(call,row.metric(),user,window.start(),row.id(),row.version(),amount,principal.administrator()||policy.limit()==null);
            mapper.reserveBucket(user,row.metric(),window.start(),amount);
        }
    }
    public void settle(UUID user, UUID call, Long tokens, Long costMicros, boolean notSent) {
        principal(user,false);
        var reservations=mapper.reservations(call);
        if(reservations.isEmpty())throw new ChatFailure(409,"QUOTA_RESERVATION_MISSING");
        if(reservations.stream().anyMatch(r->!user.equals(r.userId())))throw new ChatFailure(409,"QUOTA_CALL_SCOPE_MISMATCH");
        if(!notSent&&(tokens==null||costMicros==null)){mapper.unknown(call);return;}
        for(var reservation:reservations) {
            long actual=notSent?0:reservation.metric().equals("TOKENS")?tokens:costMicros;
            if(actual<0||actual>reservation.reserved())throw new ChatFailure(409,"PROVIDER_USAGE_EXCEEDS_RESERVATION");
            String terminal=notSent?"RELEASED":"SETTLED";
            if(Set.of("RELEASED","SETTLED").contains(reservation.status())) {
                if(!terminal.equals(reservation.status())||!Objects.equals(actual,reservation.actual()))throw new ChatFailure(409,"QUOTA_SETTLEMENT_CONFLICT");
                continue;
            }
            if(mapper.settleReservation(call,reservation.metric(),actual,terminal)!=1)throw new ChatFailure(409,"QUOTA_SETTLEMENT_CONFLICT");
            if(mapper.settleBucket(user,reservation.metric(),reservation.windowStart(),reservation.reserved(),actual)!=1)
                throw new ChatFailure(409,"QUOTA_ACCOUNTING_CONFLICT");
        }
    }
    public List<View> view(UUID user) {
        var principal=principal(user,true);var result=new ArrayList<View>();
        for(var row:policies(user)) {
            var policy=new Policy(row.id(),Metric.valueOf(row.metric()),row.limitValue(),row.timezone(),row.version());
            var window=policy.window(principal.now());
            mapper.ensureBucket(user,row.metric(),window.start(),window.end());
            var bucket=mapper.bucket(user,row.metric(),window.start());
            long reserved=Math.addExact(bucket.reserved(),mapper.earlierOutstanding(user,row.metric(),window.start()));
            boolean unlimited=principal.administrator()||policy.limit()==null;
            Long remaining=unlimited?null:Math.max(0,policy.limit()-bucket.consumed()-reserved);
            result.add(new View(policy.metric(),unlimited,unlimited?null:policy.limit(),bucket.consumed(),reserved,remaining,window.end(),policy.version()));
        }
        return List.copyOf(result);
    }
    public void revalidate(UUID user,UUID call){
        var reservations=mapper.reservations(call);
        if(reservations.isEmpty()||reservations.stream().anyMatch(r->!user.equals(r.userId())||!"RESERVED".equals(r.status())))throw new ChatFailure(409,"QUOTA_RESERVATION_MISSING");
        for(var quota:view(user))try{QuotaData.admit(quota.limit(),quota.unlimited(),quota.used(),quota.reserved(),0);}
        catch(ChatFailure denied){if(quota.metric()==Metric.COST_MICROS)throw new ChatFailure(429,"DAILY_COST_QUOTA_EXCEEDED");throw denied;}
    }
    public long configure(Change change){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Application transaction required");
        String body=ChatPersistenceSupport.json(change),hash=ChatPersistenceSupport.hash(body);
        var prior=mapper.policyCommand(change.commandId());
        if(prior!=null){if(!hash.equals(prior.requestHash()))throw new ChatFailure(409,"IDEMPOTENCY_CONFLICT");return ((Number)ChatPersistenceSupport.object(prior.afterJson()).get("version")).longValue();}
        var current=mapper.policy(change.userId(),change.metric().name());UUID id=current==null?com.agentplatform.types.Uuid7.next():current.id();
        if(current==null){
            if(change.expectedVersion()!=0||mapper.insertPolicy(id,change)!=1)throw new ChatFailure(412,"QUOTA_POLICY_VERSION_CONFLICT");
        }else if(current.version()!=change.expectedVersion()||mapper.changePolicy(id,change)!=1)throw new ChatFailure(412,"QUOTA_POLICY_VERSION_CONFLICT");
        var updated=mapper.policy(change.userId(),change.metric().name());
        mapper.auditPolicy(id,change,hash,ChatPersistenceSupport.json(current),ChatPersistenceSupport.json(updated));
        return updated.version();
    }
}
