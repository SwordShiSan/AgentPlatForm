package com.agentplatform.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;

/** R0 role lifecycle only. No user command/proxy API is enabled before R3. */
@Configuration(proxyBeanMethods = false)
@Profile({"cloud-runner", "cloud-egress"})
public class RoleBaselineConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService baselineRoleObservation(@Value("${platform.role-ready-file:/tmp/role-ready}") String readyFile) throws Exception {
        Files.writeString(Path.of(readyFile), "baseline-only\n");
        var timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleAtFixedRate(() -> System.getLogger("platform.baseline.role")
                .log(System.Logger.Level.INFO, "baseline role alive at " + Instant.now()), 0, 30, TimeUnit.SECONDS);
        return timer;
    }
}
