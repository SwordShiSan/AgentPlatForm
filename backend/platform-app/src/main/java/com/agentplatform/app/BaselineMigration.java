package com.agentplatform.app;

import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("cloud-migrate")
public class BaselineMigration {
    @Bean
    ApplicationRunner migrate(@Value("${platform.storage.jdbc-url}") String url,
            @Value("${platform.storage.user}") String user,
            @Value("${platform.storage.password-file}") String passwordFile) {
        return arguments -> Flyway.configure().dataSource(url, user, Files.readString(Path.of(passwordFile)).strip())
                .defaultSchema("pf").schemas("pf", "pf_runtime").locations("classpath:db/migration")
                .load().migrate();
    }
}
