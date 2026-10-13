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
        SpringApplication.run(PlatformApplication.class, args);
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
