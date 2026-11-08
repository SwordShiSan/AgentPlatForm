package com.agentplatform.app.capability;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionTemplate;

class ConnectorCredentialConfigurationTest {
    @TempDir Path temp;
    @Test void requiresDistinctBoundedMasterKeys() throws Exception {
        Path first = temp.resolve("model"), second = temp.resolve("mcp");
        byte[] model = new byte[32]; byte[] mcp = new byte[32]; java.util.Arrays.fill(mcp, (byte) 1);
        Files.write(first, model); Files.write(second, model);
        var configuration = new ConnectorCredentialConfiguration(); var transactions = new TransactionTemplate();
        assertThrows(IllegalArgumentException.class, () -> configuration.connectorCredentialApi(null, transactions, first.toString(), first.toString()));
        assertThrows(IllegalArgumentException.class, () -> configuration.connectorCredentialApi(null, transactions, second.toString(), first.toString()));
        Files.write(second, new byte[33]);
        assertThrows(IllegalArgumentException.class, () -> configuration.connectorCredentialApi(null, transactions, second.toString(), first.toString()));
        Files.write(second, mcp);
        assertNotNull(configuration.connectorCredentialApi(null, transactions, second.toString(), first.toString()));
    }
}
