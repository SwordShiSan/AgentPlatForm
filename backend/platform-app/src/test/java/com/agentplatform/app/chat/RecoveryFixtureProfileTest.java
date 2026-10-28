package com.agentplatform.app.chat;

import com.agentplatform.infrastructure.chat.DeepSeekChatProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryFixtureProfileTest {
    @Test void toolCapabilityRequiresExplicitProfileAndDeterministicProviderTogether() {
        var configuration=new ChatConfiguration();var environment=new MockEnvironment();
        assertThrows(IllegalArgumentException.class,()->configuration.chatProvider("http://fixture:8080",true,true,false,environment));
        environment.setActiveProfiles("chat-worker","recovery-fixture");
        assertThrows(IllegalArgumentException.class,()->configuration.chatProvider("https://api.deepseek.com",false,true,false,environment));
        var provider=(DeepSeekChatProvider)configuration.chatProvider("http://fixture:8080",true,true,false,environment);
        try(provider) {assertFalse(provider.real());}
    }
}
