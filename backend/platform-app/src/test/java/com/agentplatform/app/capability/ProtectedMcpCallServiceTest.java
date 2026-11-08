package com.agentplatform.app.capability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agentplatform.domain.capability.CapabilityBindings;
import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.ConnectorCredentialRepository;
import com.agentplatform.domain.capability.McpVersionRepository;
import com.agentplatform.domain.chat.ChatData;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.ChatRepository;
import com.agentplatform.infrastructure.capability.McpDiscoveryProcess;
import com.agentplatform.infrastructure.chat.CredentialCipher;
import com.agentplatform.types.TaskPermissionData;
import com.agentplatform.types.harness.HarnessData;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class ProtectedMcpCallServiceTest {
    private static final String POLICY = "a".repeat(64);

    @Test void invokesOnlyAfterExactOperationIsRunningApprovedAndBoundToCurrentWorkspace() throws Exception {
        try (var proxy = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            proxy.setSoTimeout(10_000);
            var connect = CompletableFuture.supplyAsync(() -> {
                try (var socket = proxy.accept()) {
                    socket.setSoTimeout(5_000);
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line = reader.readLine();
                    socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    return line;
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            });

            var fixture = authorizedFixture(new InetSocketAddress("127.0.0.1", proxy.getLocalPort()));
            ChatFailure rejected = assertThrows(ChatFailure.class, () -> fixture.service().invoke(fixture.lease(),
                    fixture.workspace(), fixture.connector(), fixture.version(), "fixture_tool", "b".repeat(64),
                    Map.of("value", "safe"), fixture.call()));
            assertEquals(503, rejected.status(), "the local proxy rejects the network request after authorization");
            assertTrue(connect.get(15, TimeUnit.SECONDS).startsWith("CONNECT fixture.invalid:443 "));
            verify(fixture.chat(), times(2)).toolRunning(fixture.lease(), fixture.call());
            verify(fixture.chat(), times(2)).operationDecision(fixture.lease(), fixture.call());
        }
    }

    @Test void doesNotCrossNetworkBoundaryForOperationNotInRunningState() throws Exception {
        try (var proxy = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            proxy.setSoTimeout(1_000);
            var fixture = authorizedFixture(new InetSocketAddress("127.0.0.1", proxy.getLocalPort()));
            when(fixture.chat().toolRunning(fixture.lease(), fixture.call())).thenReturn(false);
            ChatFailure rejected = assertThrows(ChatFailure.class, () -> fixture.service().invoke(fixture.lease(),
                    fixture.workspace(), fixture.connector(), fixture.version(), "fixture_tool", "b".repeat(64),
                    Map.of("value", "safe"), fixture.call()));
            assertEquals("MCP_OPERATION_NOT_APPROVED", rejected.getMessage());
            assertThrows(java.net.SocketTimeoutException.class, proxy::accept);
        }
    }

    private record Fixture(ProtectedMcpCallService service, ChatRepository chat, ChatData.Lease lease,
                           UUID workspace, UUID connector, UUID version, HarnessData.Call call) {}

    private static Fixture authorizedFixture(InetSocketAddress proxy) {
        UUID user = UUID.randomUUID(), space = UUID.randomUUID(), task = UUID.randomUUID();
        UUID conversation = UUID.randomUUID(), attempt = UUID.randomUUID(), owner = UUID.randomUUID();
        UUID workspace = UUID.randomUUID(), connector = UUID.randomUUID(), version = UUID.randomUUID();
        var lease = new ChatData.Lease(task, space, user, conversation, attempt, owner, 3,
                UUID.randomUUID(), null, 0, UUID.randomUUID());
        var call = new HarnessData.Call(UUID.randomUUID().toString(), "fixture_tool", Map.of("value", "safe"), false);
        var chat = mock(ChatRepository.class);
        doNothing().when(chat).check(lease);
        when(chat.toolRunning(lease, call)).thenReturn(true);
        when(chat.operationDecision(lease, call)).thenReturn(HarnessData.Decision.ALLOW);
        when(chat.permissions(any(ChatData.Actor.class), eq(task))).thenReturn(new TaskPermissionData.View(
                task.toString(), workspace.toString(), TaskPermissionData.Mode.RISK_BASED, 1, "r4-v2", false, List.of(), List.of()));

        var bindingService = mock(CapabilityBindingService.class);
        var selection = new CapabilityBindings.Selection(connector, version);
        when(bindingService.effective(new Actor(user, space), workspace)).thenReturn(
                new CapabilityBindingService.Effective(CapabilityBindings.Source.PERSONAL, List.of(selection), List.of()));
        var versions = mock(McpVersionRepository.class);
        var frozenVersion = new McpVersionRepository.Version(connector, version, 1, "PUBLISHED", 1,
                "https://fixture.invalid/mcp", "NONE", null, null, null, POLICY, 1, 1);
        when(versions.find(new Actor(user, space), connector, version, false)).thenReturn(frozenVersion);
        when(versions.eligible(new Actor(user, space), frozenVersion, POLICY)).thenReturn(true);
        when(versions.activation(new Actor(user, space), connector)).thenReturn(new McpVersionRepository.Activation(1, version));
        when(versions.tools(new Actor(user, space), connector, version)).thenReturn(List.of(
                new McpVersionRepository.Tool("fixture_tool", "fixture", "{}", "b".repeat(64))));
        var credentials = mock(ConnectorCredentialRepository.class);
        // The real bounded child runs against a loopback proxy fixture that rejects before TLS
        // or tool invocation; no remote endpoint or connector credential is used by this test.
        var process = new McpDiscoveryProcess(proxy);
        var service = new ProtectedMcpCallService(chat, bindingService, versions, credentials,
                new CredentialCipher(new byte[32]), process, new TransactionTemplate(new NoopTransactionManager()), POLICY);
        return new Fixture(service, chat, lease, workspace, connector, version, call);
    }

    private static final class NoopTransactionManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
        @Override public void commit(TransactionStatus status) {}
        @Override public void rollback(TransactionStatus status) {}
    }
}
