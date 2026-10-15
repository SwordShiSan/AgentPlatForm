package com.agentplatform.domain.identity;

public interface IdentitySecrets {
    String token();
    String hash(String value);
    String passwordHash(String password);
    boolean matches(String password, String hash);
}
