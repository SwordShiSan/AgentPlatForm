package com.agentplatform.app.capability;

import com.agentplatform.domain.capability.McpProbeRepository;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.infrastructure.capability.McpDiscovery;
import com.agentplatform.infrastructure.chat.CredentialCipher;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One read-only discovery attempt; it never invokes a remote business tool or retries an unknown call. */
public final class McpProbeExecution {
    private final McpProbeRepository repository;
    private final McpDiscovery discovery;
    private final CredentialCipher cipher;
    private final ScheduledExecutorService timers;
    public McpProbeExecution(McpProbeRepository repository, McpDiscovery discovery, CredentialCipher cipher, ScheduledExecutorService timers) {
        this.repository = Objects.requireNonNull(repository); this.discovery = Objects.requireNonNull(discovery);
        this.cipher = Objects.requireNonNull(cipher); this.timers = Objects.requireNonNull(timers);
    }
    public void run(McpProbeRepository.Command command) {
        var active = new AtomicBoolean(true);
        var renewal = timers.scheduleAtFixedRate(() -> {
            try { repository.guardAndCredential(command); } catch (RuntimeException lost) { active.set(false); }
        }, 10, 10, TimeUnit.SECONDS);
        try {
            var encrypted = repository.guardAndCredential(command);
            String secret = encrypted == null ? null : cipher.decryptConnector(encrypted);
            var authentication = new McpDiscovery.Authentication(McpDiscovery.AuthKind.valueOf(command.authKind()), command.apiKeyHeader(), secret);
            var discovered = discovery.discover(command.endpoint(), authentication);
            if (!active.get()) throw new FileFailure(409, "MCP_PROBE_STALE_OWNER");
            repository.ready(command, discovered.stream().map(tool -> new McpProbeRepository.Tool(tool.name(), tool.description(),
                    tool.schemaJson(), tool.schemaSha256())).toList());
        } catch (FileFailure failure) {
            try {
                if (failure.status() == 422 || failure.status() == 403) repository.failed(command, failure.getMessage());
                else repository.unknown(command, "MCP_PROBE_RESULT_UNKNOWN");
            } catch (RuntimeException unresolved) { failure.addSuppressed(unresolved); }
        } catch (ChatFailure failure) {
            try { repository.failed(command, "CONNECTOR_CREDENTIAL_DECRYPTION_FAILED"); }
            catch (RuntimeException unresolved) { failure.addSuppressed(unresolved); }
        } catch (RuntimeException uncertain) {
            try { repository.unknown(command, "MCP_PROBE_RESULT_UNKNOWN"); }
            catch (RuntimeException unresolved) { uncertain.addSuppressed(unresolved); }
        } finally { active.set(false); renewal.cancel(false); }
    }
}
