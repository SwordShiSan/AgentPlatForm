package com.agentplatform.infrastructure.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.agentplatform.domain.chat.ChatData.Credential;
import com.agentplatform.domain.chat.ChatFailure;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CredentialCipherTest {
    private final byte[] master = new byte[32];
    private final CredentialCipher cipher = new CredentialCipher(master);
    private static final String SENTINEL = "credential-test-sentinel-no-real-provider-key";

    @Test void platformAndUserSecretsRoundTripWithoutPlaintextAtRest() {
        for (String scope : new String[]{"PLATFORM", "USER"}) {
            boolean user = scope.equals("USER");
            var encrypted = cipher.encrypt(UUID.randomUUID(), scope, user ? UUID.randomUUID() : null,
                    user ? UUID.randomUUID() : null, SENTINEL);
            assertEquals(SENTINEL, cipher.decrypt(encrypted));
            assertFalse(new String(encrypted.ciphertext(), StandardCharsets.UTF_8).contains(SENTINEL));
            assertFalse(encrypted.toString().contains(SENTINEL));
            assertEquals(12, encrypted.nonce().length);
            assertEquals(SENTINEL.getBytes(StandardCharsets.UTF_8).length + 16, encrypted.ciphertext().length);
        }
    }

    @Test void independentEncryptionUsesFreshNonceAndDefensiveCopies() {
        var id = UUID.randomUUID();
        var first = cipher.encrypt(id, "PLATFORM", null, null, SENTINEL);
        var second = cipher.encrypt(id, "PLATFORM", null, null, SENTINEL);
        assertFalse(Arrays.equals(first.nonce(), second.nonce()));
        assertFalse(Arrays.equals(first.ciphertext(), second.ciphertext()));
        byte[] output = first.ciphertext();
        Arrays.fill(output, (byte) 0);
        Arrays.fill(first.nonce(), (byte) 0);
        Arrays.fill(master, (byte) 99);
        assertEquals(SENTINEL, cipher.decrypt(first));
    }

    @Test void changingIdentityScopeOwnerNonceOrCiphertextRejectsDecryption() {
        var value = cipher.encrypt(UUID.randomUUID(), "USER", UUID.randomUUID(), UUID.randomUUID(), SENTINEL);
        reject(new Credential(UUID.randomUUID(), value.scope(), value.spaceId(), value.ownerId(), value.ciphertext(), value.nonce(), 1, 1));
        reject(new Credential(value.id(), value.scope(), UUID.randomUUID(), value.ownerId(), value.ciphertext(), value.nonce(), 1, 1));
        reject(new Credential(value.id(), value.scope(), value.spaceId(), UUID.randomUUID(), value.ciphertext(), value.nonce(), 1, 1));
        reject(new Credential(value.id(), "PLATFORM", null, null, value.ciphertext(), value.nonce(), 1, 1));
        reject(new Credential(value.id(), value.scope(), value.spaceId(), value.ownerId(), value.ciphertext(), value.nonce(), 2, 1));
        byte[] altered = value.ciphertext(); altered[0] ^= 1;
        reject(new Credential(value.id(), value.scope(), value.spaceId(), value.ownerId(), altered, value.nonce(), 1, 1));
        byte[] nonce = value.nonce(); nonce[0] ^= 1;
        reject(new Credential(value.id(), value.scope(), value.spaceId(), value.ownerId(), value.ciphertext(), nonce, 1, 1));
        assertThrows(ChatFailure.class, () -> new CredentialCipher(new byte[32]).decrypt(
                new CredentialCipher(new byte[]{1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0})
                        .encrypt(UUID.randomUUID(), "PLATFORM", null, null, SENTINEL)));
    }

    @Test void scopeMetadataCannotRepresentOrphanedUserOrPlatformOwner() {
        assertThrows(IllegalArgumentException.class, () -> cipher.encrypt(UUID.randomUUID(), "USER", null, UUID.randomUUID(), SENTINEL));
        assertThrows(IllegalArgumentException.class, () -> cipher.encrypt(UUID.randomUUID(), "PLATFORM", UUID.randomUUID(), null, SENTINEL));
        assertThrows(IllegalArgumentException.class, () -> cipher.encrypt(UUID.randomUUID(), "UNKNOWN", null, null, SENTINEL));
        assertThrows(IllegalArgumentException.class, () -> new CredentialCipher(new byte[31]));
    }

    private void reject(Credential value) {
        var failure = assertThrows(ChatFailure.class, () -> cipher.decrypt(value));
        assertEquals("CREDENTIAL_DECRYPTION_REJECTED", failure.code());
        assertFalse(failure.getMessage().contains(SENTINEL));
    }
}
