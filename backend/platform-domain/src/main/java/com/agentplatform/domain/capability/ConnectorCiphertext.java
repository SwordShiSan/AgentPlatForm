package com.agentplatform.domain.capability;

import java.util.UUID;

/** Encrypted connector secret with authenticated owner metadata; no plaintext projection. */
public record ConnectorCiphertext(UUID id, UUID spaceId, UUID ownerId, byte[] ciphertext, byte[] nonce, int keyVersion, long revision) {
    public ConnectorCiphertext {
        if (id == null || spaceId == null || ownerId == null || ciphertext == null || nonce == null || nonce.length != 12
                || keyVersion < 1 || revision < 1) throw new IllegalArgumentException("Invalid connector credential metadata");
        ciphertext = ciphertext.clone(); nonce = nonce.clone();
    }
    @Override public byte[] ciphertext() { return ciphertext.clone(); }
    @Override public byte[] nonce() { return nonce.clone(); }
    @Override public String toString() { return "ConnectorCiphertext[id=" + id + ", revision=" + revision + "]"; }
}
