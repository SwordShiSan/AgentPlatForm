package com.agentplatform.infrastructure.identity;

import com.agentplatform.domain.identity.IdentityFailure;
import com.agentplatform.domain.identity.IdentitySecrets;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

public final class ArgonIdentitySecrets implements IdentitySecrets {
    private final SecureRandom random = new SecureRandom();
    private final Semaphore slot = new Semaphore(1);
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 65536, 3);

    @Override public String token() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    @Override public String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Override public String passwordHash(String password) { return guarded(() -> encoder.encode(password)); }
    @Override public boolean matches(String password, String hash) { return guarded(() -> encoder.matches(password, hash)); }
    private <T> T guarded(java.util.function.Supplier<T> work) {
        if (!slot.tryAcquire()) throw new IdentityFailure(429, "AUTHENTICATION_BUSY");
        try { return work.get(); } finally { slot.release(); }
    }
}
