package com.agentplatform.app.chat;

import com.agentplatform.infrastructure.chat.DeepSeekChatProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryFixtureProfileTest {
    @Test void toolCapabilityRequiresExplicitProfileAndDeterministicProviderTogether() {
        var configuration=new ChatConfiguration();var environment=new MockEnvironment();
        assertThrows(IllegalArgumentException.class,()->configuration.chatProvider("http://fixture:8080",true,true,environment));
        environment.setActiveProfiles("chat-worker","recovery-fixture");
        assertThrows(IllegalArgumentException.class,()->configuration.chatProvider("https://api.deepseek.com",false,true,environment));
        var provider=(DeepSeekChatProvider)configuration.chatProvider("http://fixture:8080",true,true,environment);
        try(provider) {assertFalse(provider.real());}
    }
}
