package com.agentplatform.app.identity;

import com.agentplatform.api.identity.IdentityApi;
import com.agentplatform.domain.identity.IdentitySecrets;
import com.agentplatform.domain.identity.IdentityStore;
import com.agentplatform.infrastructure.identity.ArgonIdentitySecrets;
import com.agentplatform.infrastructure.adapter.repository.identity.MyBatisIdentityStore;
import com.agentplatform.infrastructure.dao.identity.IdentityMapper;
import org.springframework.transaction.support.TransactionTemplate;
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
    @Bean IdentityStore identityStore(IdentityMapper mapper, TransactionTemplate transactions) {
        return new TransactionalIdentityStore(new MyBatisIdentityStore(mapper), transactions);
    }
    @Bean @Profile("identity-local")
    IdentityApi identityApi(IdentityStore store, IdentitySecrets secrets,
            @Value("${platform.identity.origins}") String origins,
            @Value("${platform.identity.secure-cookie:true}") boolean secure,
            @Value("${platform.identity.http-deployment:false}") boolean httpDeployment) {
        Set<String> allowed = Set.of(origins.split(","));
        for (String origin : allowed) {
            URI uri = URI.create(origin);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())
                    || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) throw new IllegalArgumentException("Invalid identity origin");
            if (httpDeployment && (!Set.of("http://agentplatform.lailihao.cloud", "http://124.222.55.140").contains(origin) || secure)) throw new IllegalArgumentException("HTTP deployment requires approved exact origins and non-Secure cookie");
            if (!secure && !httpDeployment && !("http".equals(uri.getScheme()) && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()))) throw new IllegalArgumentException("Non-Secure cookies require explicit localhost HTTP origins");
        }
        return new IdentityService(store, secrets, Clock.systemUTC(), allowed);
    }
}
