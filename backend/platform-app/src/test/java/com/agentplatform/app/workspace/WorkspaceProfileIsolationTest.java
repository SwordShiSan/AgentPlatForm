package com.agentplatform.app.workspace;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceProfileIsolationTest {
    @TempDir Path directory;
    private ApplicationContextRunner runner(String...profiles) {
        return new ApplicationContextRunner().withUserConfiguration(WorkspaceMessagingConfiguration.StandaloneConnections.class)
                .withInitializer(context->context.getEnvironment().setActiveProfiles(profiles));
    }
    @Test void nestedConfigurationCannotLeakIntoMigrationOperatorOrChat() {
        for(String profile:new String[]{"cloud-migrate","identity-ops","chat-worker","chat-local"}) {
            runner(profile).withPropertyValues("platform.workspace.enabled=true").run(context->{
                assertNull(context.getStartupFailure());assertEquals(0,context.getBeansOfType(CachingConnectionFactory.class).size());
            });
        }
        runner("workspace-api").withPropertyValues("platform.workspace.enabled=false").run(context->{
            assertNull(context.getStartupFailure());assertEquals(0,context.getBeansOfType(CachingConnectionFactory.class).size());
        });
    }
    @Test void standaloneWorkerGetsExactlyOneManagedFactoryWithoutChatSecrets() throws Exception {
        Path password=directory.resolve("mq-password");Files.writeString(password,"deterministic-not-production");
        runner("workspace-worker").withPropertyValues("platform.workspace.enabled=true","platform.workspace.messaging.host=127.0.0.1",
                "platform.workspace.messaging.port=15698","platform.workspace.messaging.user=r31","platform.workspace.messaging.password-file="+password)
                .run(context->{assertNull(context.getStartupFailure());assertEquals(1,context.getBeansOfType(CachingConnectionFactory.class).size());});
    }
}
