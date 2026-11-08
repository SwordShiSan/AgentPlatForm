package com.agentplatform.infrastructure.chat;

import static org.junit.jupiter.api.Assertions.*;
import com.agentplatform.domain.capability.ConnectorCiphertext;
import com.agentplatform.domain.chat.ChatFailure;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConnectorCredentialCipherTest {
    @Test void bindsCiphertextToConnectorOwnerAndDedicatedKey() {
        byte[] key = new byte[32]; java.util.Arrays.fill(key, (byte) 7);
        var cipher = new CredentialCipher(key); UUID id = UUID.randomUUID(), space = UUID.randomUUID(), owner = UUID.randomUUID();
        var encrypted = cipher.encryptConnector(id, space, owner, "fixture-sentinel");
        assertEquals("fixture-sentinel", cipher.decryptConnector(encrypted));
        assertFalse(encrypted.toString().contains("fixture-sentinel"));
        assertThrows(ChatFailure.class, () -> cipher.decryptConnector(new ConnectorCiphertext(id, space, UUID.randomUUID(),
                encrypted.ciphertext(), encrypted.nonce(), 1, 1)));
        assertThrows(ChatFailure.class, () -> new CredentialCipher(new byte[32]).decryptConnector(encrypted));
    }
}
