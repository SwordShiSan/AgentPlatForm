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
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"deepseek-flash","deepseek-v4-pro"})
    void sdkDeliversTextBeforeFinalUsageAndDoesNotExposeReasoning(String modelName) throws Exception {
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
            var future = provider.stream(modelName,"provider-test-sentinel", List.of(Message.text(Role.USER, "hello")), value -> {
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
            assertEquals(modelName,body.get("model").asText());
            var strict = new com.fasterxml.jackson.databind.ObjectMapper();
            strict.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            assertDoesNotThrow(() -> strict.readTree(request.get()), "Provider wire JSON must not repeat SDK-owned fields");
            assertEquals(modelName, body.path("model").asText()); assertTrue(body.path("stream").asBoolean());
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
        assertThrows(IllegalArgumentException.class,()->new DeepSeekChatProvider("https://api.deepseek.com",false,true));
    }

    @Test void fixedFixtureToolsUseSdkFragmentAccumulatorAndRemainDisabledByDefault() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            try(var output=exchange.getResponseBody()) {
                String data="data: {\"id\":\"tool-fixture\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"stable-operation\",\"type\":\"function\",\"function\":{\"name\":\"recovery_probe\",\"arguments\":\"{\\\"text\\\":\"}}]},\"finish_reason\":null}]}\n\n"
                    +"data: {\"id\":\"tool-fixture\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"one\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                    +"data: {\"id\":\"tool-fixture\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3,\"total_tokens\":13}}\n\ndata: [DONE]\n\n";
                output.write(data.getBytes(StandardCharsets.UTF_8));
            }catch(java.io.IOException closed) { /* Default policy may close the rejected response. */ }
        });server.start();
        try {
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort();
            try(var provider=new DeepSeekChatProvider(endpoint,true,true)) {
                var exchange=provider.open("deepseek-flash","fixture-sentinel",List.of(Message.text(Role.USER,"fixture:recovery-tool")),text->fail("Tool round has no public text"));
                var result=exchange.result().toCompletableFuture().get(8,TimeUnit.SECONDS);
                assertEquals("tool_calls",result.round().finishReason());assertTrue(result.usageKnown());
                assertEquals(1,result.round().calls().size());assertEquals("stable-operation",result.round().calls().getFirst().id());
                assertEquals(java.util.Map.of("text","one"),result.round().calls().getFirst().arguments());
                exchange.terminated().toCompletableFuture().get(5,TimeUnit.SECONDS);
            }
            try(var closed=new DeepSeekChatProvider(endpoint,true)) {
                assertThrows(ExecutionException.class,()->closed.stream("fixture-sentinel",List.of(Message.text(Role.USER,"fixture:recovery-tool")),text->{}).toCompletableFuture().get(8,TimeUnit.SECONDS));
            }
        }finally {server.stop(0);}
    }

    @Test void retryableHttpErrorsRemainOnePhysicalRequestPerLedgerCall() throws Exception {
        for(int status : List.of(400, 429, 503)) {
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
                assertEquals(status == 400 ? "PROVIDER_REQUEST_REJECTED" : status == 429 ? "PROVIDER_RATE_LIMITED" : "PROVIDER_SERVER_REJECTED",
                        ((com.agentplatform.domain.chat.ChatFailure) failure.getCause()).code());
                assertEquals(1, calls.get(), "Only platform ledger may schedule a subsequent physical request");
            } finally { server.stop(0); }
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"402,PROVIDER_BALANCE_EXHAUSTED","429,PROVIDER_RATE_LIMITED","401,PROVIDER_AUTH_REJECTED","503,PROVIDER_SERVER_REJECTED"})
    void classifiesTypedProviderFailureWithoutLeakingBody(int status,String expected) throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            exchange.getRequestBody().readAllBytes();byte[] data="{\"error\":{\"message\":\"secret-sentinel\",\"type\":\"error\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,data.length);exchange.getResponseBody().write(data);exchange.close();
        });server.start();
        try {
            var future=new DeepSeekChatProvider("http://127.0.0.1:"+server.getAddress().getPort(),true)
                .stream("sentinel",List.of(Message.text(Role.USER,"hello")),text->{}).toCompletableFuture();
            var thrown=assertThrows(ExecutionException.class,()->future.get(10,TimeUnit.SECONDS));
            var failure=assertInstanceOf(com.agentplatform.domain.chat.ChatFailure.class,thrown.getCause());
            assertEquals(expected,failure.code());assertFalse(failure.getMessage().contains("secret-sentinel"));
        } finally {server.stop(0);}
    }

}
