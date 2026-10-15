package com.agentplatform.types;

import java.security.SecureRandom;
import java.util.UUID;

public final class Uuid7 {
    private static final SecureRandom RANDOM = new SecureRandom();
    private Uuid7() {}
    public static UUID next() {
        long most = (System.currentTimeMillis() & 0xffffffffffffL) << 16;
        most |= 0x7000L | (RANDOM.nextInt(4096));
        return new UUID(most, (RANDOM.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L);
    }
}
