package com.agentplatform.app.identity;

import com.agentplatform.api.identity.IdentityApi;
import com.agentplatform.domain.identity.IdentitySecrets;
import com.agentplatform.domain.identity.IdentityStore;
import com.agentplatform.infrastructure.identity.ArgonIdentitySecrets;
import com.agentplatform.infrastructure.identity.JdbcIdentityStore;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"identity-local", "identity-ops"})
public class IdentityConfiguration {
    @Bean IdentitySecrets identitySecrets() { return new ArgonIdentitySecrets(); }
    @Bean IdentityStore identityStore(@Value("${platform.storage.jdbc-url}") String url,
            @Value("${platform.storage.user}") String user, @Value("${platform.storage.password-file}") String file,
            org.springframework.beans.factory.ObjectProvider<javax.sql.DataSource> pool) throws Exception {
        var source = pool.getIfAvailable();
        return source == null ? new JdbcIdentityStore(url, user, Files.readString(Path.of(file)).strip()) : new JdbcIdentityStore(source);
    }
    @Bean @Profile("identity-local")
    IdentityApi identityApi(IdentityStore store, IdentitySecrets secrets,
            @Value("${platform.identity.origins}") String origins,
            @Value("${platform.identity.secure-cookie:true}") boolean secure) {
        Set<String> allowed = Set.of(origins.split(","));
        for (String origin : allowed) {
            URI uri = URI.create(origin);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())
                    || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) throw new IllegalArgumentException("Invalid identity origin");
            if (!secure && !("http".equals(uri.getScheme()) && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()))) throw new IllegalArgumentException("Non-Secure cookies require explicit localhost HTTP origins");
        }
        return new IdentityService(store, secrets, Clock.systemUTC(), allowed);
    }
}
