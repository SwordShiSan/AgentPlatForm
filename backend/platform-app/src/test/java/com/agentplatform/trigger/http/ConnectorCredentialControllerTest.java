package com.agentplatform.trigger.http;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ConnectorCredentialControllerTest {
    @Test void requestObjectDoesNotRenderSecret() {
        assertFalse(new ConnectorCredentialController.SecretBody("fixture-private").toString().contains("fixture-private"));
    }
}
