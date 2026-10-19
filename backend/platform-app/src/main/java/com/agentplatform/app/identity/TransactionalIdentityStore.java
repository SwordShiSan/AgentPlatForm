package com.agentplatform.app.identity;

import com.agentplatform.domain.identity.IdentityStore;
import com.agentplatform.domain.identity.IdentityFailure;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

public final class TransactionalIdentityStore implements IdentityStore {
    private final IdentityStore delegate;
    private final TransactionTemplate transactions;
    public TransactionalIdentityStore(IdentityStore delegate, TransactionTemplate transactions) {
        this.delegate = delegate; this.transactions = transactions;
    }
    private <T> T tx(Supplier<T> work) {
        try { return transactions.execute(status -> work.get()); }
        catch (DuplicateKeyException conflict) { throw new IdentityFailure(409, "IDENTITY_CONFLICT"); }
        catch (DataAccessException | org.springframework.transaction.TransactionException unavailable) { throw new IdentityFailure(503, "IDENTITY_UNAVAILABLE"); }
    }
    @Override public UUID provision(UUID request, String fingerprint, String name, String hash, boolean bootstrap) { return tx(() -> delegate.provision(request, fingerprint, name, hash, bootstrap)); }
    @Override public User findUser(String name) { return tx(() -> delegate.findUser(name)); }
    @Override public User user(UUID id) { return tx(() -> delegate.user(id)); }
    @Override public List<Space> spaces(UUID user) { return tx(() -> delegate.spaces(user)); }
    @Override public Space space(UUID user, UUID space) { return tx(() -> delegate.space(user, space)); }
    @Override public Session session(String token, Instant now) { return tx(() -> delegate.session(token, now)); }
    @Override public void createSession(Session session) { tx(() -> { delegate.createSession(session); return null; }); }
    @Override public void csrf(String token, String csrf) { tx(() -> { delegate.csrf(token, csrf); return null; }); }
    @Override public void authenticateSession(String old, String hash, Session session, Instant now) { tx(() -> { delegate.authenticateSession(old, hash, session, now); return null; }); }
    @Override public void revoke(String token, Instant now) { tx(() -> { delegate.revoke(token, now); return null; }); }
    @Override public void changeUser(String name, String hash, boolean disable, Instant now) { tx(() -> { delegate.changeUser(name, hash, disable, now); return null; }); }
    @Override public boolean rate(String key, int limit, int seconds, Instant now) { return tx(() -> delegate.rate(key, limit, seconds, now)); }
    @Override public boolean accountLocked(String key, Instant now) { return tx(() -> delegate.accountLocked(key, now)); }
    @Override public void accountResult(String key, boolean success, Instant now) { tx(() -> { delegate.accountResult(key, success, now); return null; }); }
    @Override public boolean ready() {
        try { return tx(delegate::ready); } catch (IdentityFailure unavailable) { return false; }
    }
}
