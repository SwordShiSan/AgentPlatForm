package com.agentplatform.app;

import com.agentplatform.api.health.ReadinessQuery;
import com.agentplatform.domain.health.ReadinessProbe;
import com.agentplatform.infrastructure.health.DependencyReadinessProbe;
import com.agentplatform.infrastructure.health.DependencyReadinessProbe.Endpoint;
import com.agentplatform.infrastructure.storage.PostgresStateStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
public class CloudBaselineConfiguration {
    @Bean
    @Profile({"cloud-api", "cloud-worker"})
    PostgresStateStorage postgresStateStorage(@Value("${platform.storage.jdbc-url}") String url,
            @Value("${platform.storage.user}") String user,
            @Value("${platform.storage.password-file}") String passwordFile) throws Exception {
        return new PostgresStateStorage(url, user, Files.readString(Path.of(passwordFile)).strip());
    }

    @Bean
    @Profile({"cloud-api", "cloud-worker"})
    ReadinessProbe cloudReadiness(PostgresStateStorage storage) {
        return new DependencyReadinessProbe(storage, List.of(new Endpoint("redis", 6379),
                new Endpoint("rabbitmq", 5672), new Endpoint("s3", 8333)));
    }

    @Bean
    @Profile("!cloud-api & !cloud-worker & !identity-local")
    ReadinessProbe unavailableReadiness() { return () -> false; }

    @Bean
    @Profile("identity-local & !cloud-api & !cloud-worker")
    ReadinessProbe identityReadiness(com.agentplatform.domain.identity.IdentityStore store) { return store::ready; }

    @Bean
    ReadinessQuery readinessQuery(ReadinessProbe probe) { return probe::ready; }
}
