package com.agentplatform.app;

import com.agentplatform.api.health.HealthQuery;
import com.agentplatform.api.health.HealthResponse;
import com.agentplatform.domain.health.HealthProbe;
import com.agentplatform.infrastructure.health.BootstrapHealthProbe;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = "com.agentplatform")
public class PlatformApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(PlatformApplication.class, args);
        if (context.getEnvironment().matchesProfiles("cloud-migrate", "identity-ops", "chat-ops", "workspace-environment-ops", "workspace-environment-fixture")) {
            System.exit(SpringApplication.exit(context));
        }
    }

    @Bean
    HealthProbe healthProbe() {
        return new BootstrapHealthProbe();
    }

    @Bean
    HealthQuery healthQuery(HealthProbe probe) {
        return () -> new HealthResponse(probe.status());
    }
}
