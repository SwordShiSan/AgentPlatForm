package com.agentplatform.app.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** A single managed pool and transaction manager per API, worker or operator process. */
@Configuration(proxyBeanMethods = false)
@Profile({"identity-local", "identity-ops", "chat-local", "chat-worker", "chat-ops", "workspace-api", "workspace-worker", "workspace-runner", "workspace-environment-ops", "workspace-environment-fixture"})
@EnableConfigurationProperties(StorageProperties.class)
@MapperScan(basePackages = "com.agentplatform.infrastructure.dao", annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class PersistenceConfiguration {
    @Bean(destroyMethod = "close")
    HikariDataSource platformDataSource(StorageProperties properties) throws Exception {
        var config = new HikariConfig();
        config.setPoolName("platform-persistence");
        config.setJdbcUrl(properties.jdbcUrl());
        config.setUsername(properties.user());
        config.setPassword(Files.readString(properties.passwordFile()).strip());
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(2000);
        config.setValidationTimeout(1000);
        config.setIdleTimeout(120000);
        config.setMaxLifetime(600000);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    @Bean PlatformTransactionManager platformTransactionManager(DataSource source) { return new JdbcTransactionManager(source); }
    @Bean TransactionTemplate platformTransactions(PlatformTransactionManager manager) { return new TransactionTemplate(manager); }
}
