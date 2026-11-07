package com.agentplatform.app.capability;

import com.agentplatform.domain.capability.McpProbeRepository;
import com.agentplatform.infrastructure.capability.McpDiscovery;
import com.agentplatform.infrastructure.capability.SpringMcpMessaging;
import com.agentplatform.infrastructure.chat.CredentialCipher;
import com.agentplatform.infrastructure.dao.capability.McpProbeMapper;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("workspace-worker")
@ConditionalOnProperty(name = {"platform.capabilities.enabled", "platform.mcp.enabled"}, havingValue = "true")
public class McpProbeConfiguration {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(McpProbeConfiguration.class);
    @Bean McpProbeRepository mcpProbeRepository(McpProbeMapper mapper, TransactionTemplate transactions,
                                                @Value("${platform.mcp.policy-hash}") String policyHash) {
        return new TransactionalMcpProbeRepository(mapper, transactions, policyHash);
    }
    @Bean ThreadPoolTaskScheduler mcpProbeTimers() {
        var timers = new ThreadPoolTaskScheduler(); timers.setPoolSize(2); timers.setThreadNamePrefix("mcp-probe-");
        timers.setRemoveOnCancelPolicy(true); timers.setWaitForTasksToCompleteOnShutdown(false); return timers;
    }
    @Bean Declarables mcpProbeTopology() {
        var exchange = new DirectExchange(SpringMcpMessaging.EXCHANGE, true, false);
        var queue = QueueBuilder.durable(SpringMcpMessaging.QUEUE).quorum().build();
        return new Declarables(exchange, queue, BindingBuilder.bind(queue).to(exchange).with(SpringMcpMessaging.ROUTE));
    }
    @Bean(destroyMethod="close") SpringMcpMessaging mcpMessaging(CachingConnectionFactory connections, McpProbeRepository repository) {
        return new SpringMcpMessaging(connections, repository);
    }
    @Bean McpProbeExecution mcpProbeExecution(McpProbeRepository repository, @Qualifier("mcpProbeTimers") ThreadPoolTaskScheduler timers,
            @Value("${platform.mcp.egress-host}") String host, @Value("${platform.mcp.egress-port}") int port,
            @Value("${platform.mcp.master-key-file}") String mcpFile, @Value("${platform.chat.master-key-file}") String modelFile) throws java.io.IOException {
        if (host == null || host.isBlank() || port < 1 || port > 65535) throw new IllegalArgumentException("MCP_EGRESS_REQUIRED");
        byte[] key = IndependentMcpKeyLoader.load(mcpFile, modelFile);
        try { return new McpProbeExecution(repository, new McpDiscovery(InetSocketAddress.createUnresolved(host, port)),
                    new CredentialCipher(key), timers.getScheduledExecutor()); }
        finally { Arrays.fill(key, (byte) 0); }
    }
    @Bean SmartLifecycle mcpProbeLifecycle(McpProbeRepository repository, SpringMcpMessaging messaging,
            McpProbeExecution execution, @Qualifier("mcpProbeTimers") ThreadPoolTaskScheduler timers) {
        return new SmartLifecycle() {
            private volatile boolean running;
            private java.util.concurrent.ScheduledFuture<?> relay;
            private final UUID owner = UUID.randomUUID();
            @Override public void start() {
                running = true; messaging.start(execution::run);
                relay = timers.scheduleWithFixedDelay(() -> {
                    if (!running) return;
                    try {
                        var item = repository.nextPublication(owner);
                        if (item != null) { messaging.publish(item); repository.published(item); }
                    } catch (RuntimeException unavailable) { LOG.debug("MCP discovery relay will retry after its lease"); }
                }, Duration.ofMillis(500));
            }
            @Override public void stop() { running = false; if (relay != null) relay.cancel(false); messaging.close(); }
            @Override public boolean isRunning() { return running; }
        };
    }
}
