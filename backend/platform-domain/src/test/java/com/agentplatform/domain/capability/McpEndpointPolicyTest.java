package com.agentplatform.domain.capability;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpEndpointPolicyTest {
    @Test void acceptsPublicHttpsSyntaxWithoutResolvingNames() {
        assertEquals("https://example.com/mcp", McpEndpointPolicy.external("https://example.com/mcp").toString());
        assertEquals(8443, McpEndpointPolicy.external("https://example.com:8443/mcp").getPort());
        assertDoesNotThrow(() -> McpEndpointPolicy.external("https://8.8.8.8/mcp"));
    }
    @Test void rejectsSecretsAndNonPublicLiteralAddresses() {
        for (String input : List.of("http://example.com/mcp", "https://u:p@example.com/mcp", "https://example.com/mcp?token=x",
                "https://example.com/#fragment", "https://localhost/mcp", "https://127.0.0.1/mcp", "https://127.1/mcp",
                "https://10.0.0.1/mcp", "https://100.64.0.1/mcp", "https://169.254.169.254/mcp",
                "https://192.0.2.1/mcp", "https://198.18.0.1/mcp", "https://[::1]/mcp", "https://[2001:2::1]/mcp",
                "https://[2001:db8::1]/mcp", "https://[2002::1]/mcp", "https://[3fff::1]/mcp"))
            assertThrows(IllegalArgumentException.class, () -> McpEndpointPolicy.external(input), input);
    }
}
