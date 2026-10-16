package com.agentplatform.infrastructure.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.agentplatform.types.harness.HarnessData.Message;
import com.agentplatform.types.harness.HarnessData.Role;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class DeepSeekChatProviderTest {
    @Test void sdkDeliversTextBeforeFinalUsageAndDoesNotExposeReasoning() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1); var firstText = new CountDownLatch(1);
        var request = new AtomicReference<String>(); var authorization = new AtomicReference<String>();
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(("data: {\"id\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"你好😀\",\"reasoning_content\":\"PRIVATE_REASONING_SENTINEL\"},\"finish_reason\":null}]}\n\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
                try { release.await(8, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                output.write(("data: {\"id\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: {\"id\":\"fixture\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3,\"total_tokens\":13}}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        var received = new StringBuffer();
        try {
            var provider = new DeepSeekChatProvider("http://127.0.0.1:" + server.getAddress().getPort(), true);
            var future = provider.stream("provider-test-sentinel", List.of(Message.text(Role.USER, "hello")), value -> {
                received.append(value); firstText.countDown();
            }).toCompletableFuture();
            assertTrue(firstText.await(5, TimeUnit.SECONDS), "SDK must emit before provider completion");
            assertFalse(future.isDone()); release.countDown();
            var result = future.get(8, TimeUnit.SECONDS);
            assertEquals("你好😀", received.toString()); assertEquals("你好😀", result.round().text());
            assertTrue(result.usageKnown()); assertEquals(10, result.round().inputTokens()); assertEquals(3, result.round().outputTokens());
            assertEquals("stop", result.round().finishReason());
            assertEquals("Bearer provider-test-sentinel", authorization.get());
            var body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(request.get());
            assertEquals("deepseek-flash", body.path("model").asText()); assertTrue(body.path("stream").asBoolean());
            assertTrue(body.path("stream_options").path("include_usage").asBoolean());
            assertEquals("disabled", body.path("thinking").path("type").asText());
            assertEquals(1024, body.path("max_tokens").asInt());
            assertFalse(result.round().text().contains("PRIVATE_REASONING_SENTINEL"));
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void arbitraryEndpointsAndInsecureRealEndpointAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DeepSeekChatProvider("http://api.deepseek.com", false));
        assertThrows(IllegalArgumentException.class, () -> new DeepSeekChatProvider("https://evil.example", false));
        assertThrows(IllegalArgumentException.class, () -> new DeepSeekChatProvider("http://evil.example", true));
        assertFalse(new DeepSeekChatProvider("http://localhost:12345", true).real());
        assertTrue(new DeepSeekChatProvider("https://api.deepseek.com", false).real());
    }

    @Test void retryableHttpErrorsRemainOnePhysicalRequestPerLedgerCall() throws Exception {
        for(int status : List.of(429, 503)) {
            var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var calls = new AtomicInteger();
            server.createContext("/chat/completions", exchange -> {
                calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(status, -1); exchange.close();
            });
            server.start();
            try {
                var provider = new DeepSeekChatProvider("http://127.0.0.1:" + server.getAddress().getPort(), true);
                var failure = assertThrows(ExecutionException.class, () -> provider.stream("fixture-only",
                        List.of(Message.text(Role.USER, "retry")), text -> fail("No text expected"))
                        .toCompletableFuture().get(8, TimeUnit.SECONDS));
                assertInstanceOf(com.agentplatform.domain.chat.ChatFailure.class, failure.getCause());
                assertEquals(status == 429 ? "PROVIDER_RATE_LIMITED" : "PROVIDER_SERVER_REJECTED",
                        ((com.agentplatform.domain.chat.ChatFailure) failure.getCause()).code());
                assertEquals(1, calls.get(), "Only platform ledger may schedule a subsequent physical request");
            } finally { server.stop(0); }
        }
    }
}
