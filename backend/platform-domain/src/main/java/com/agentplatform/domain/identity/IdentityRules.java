package com.agentplatform.domain.identity;

import java.text.Normalizer;
import java.util.Locale;

public final class IdentityRules {
    private IdentityRules() {}

    public static String username(String value) {
        if (value == null || value.length() > 256) throw new IdentityFailure(400, "INVALID_USERNAME");
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 3 || length > 64 || !normalized.codePoints().allMatch(c ->
                Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-')) {
            throw new IdentityFailure(400, "INVALID_USERNAME");
        }
        return normalized;
    }

    public static void password(String value) {
        if (value == null || value.length() < 12 || value.length() > 128) {
            throw new IdentityFailure(400, "INVALID_PASSWORD_LENGTH");
        }
    }
}
