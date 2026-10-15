package com.agentplatform.app;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthHttpTest {
    @LocalServerPort
    private int port;

    @Autowired private ApplicationContext context;

    @Test
    void healthIsRealJsonAndDoesNotNeedDatabase() throws Exception {
        var response = get("/api/v1/health");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("application/json"));
        var body = JsonMapper.builder().build().readTree(response.body());
        assertEquals("UP", body.get("status").asText());
        assertEquals(1, body.size());
    }

    @Test
    void bootstrapReadinessIsFailClosedAndDoesNotLeakDependencies() throws Exception {
        var response = get("/api/v1/readiness");
        assertEquals(503, response.statusCode());
        var body = JsonMapper.builder().build().readTree(response.body());
        assertEquals("NOT_READY", body.get("status").asText());
        assertEquals(1, body.size());
    }

    @Test
    void platformDoesNotExposeMcpServerTransports() throws Exception {
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type != null) assertFalse(type.getName().matches(".*(McpServer|ServerTransport).*"), type.getName());
        }
        for (String path : new String[]{"/mcp", "/sse", "/api/v1/mcp", "/api/v1/sse"}) {
            assertEquals(404, get(path).statusCode(), path);
            var post = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
                    .build();
            assertEquals(404, HttpClient.newHttpClient().send(post, HttpResponse.BodyHandlers.ofString()).statusCode(), path);
        }
    }

    @Test
    void unknownApiReturnsSanitizedProblem() throws Exception {
        var response = get("/api/v1/missing");
        assertEquals(404, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("application/problem+json"));
        var body = JsonMapper.builder().build().readTree(response.body());
        assertEquals("RESOURCE_NOT_FOUND", body.get("code").asText());
        assertEquals(404, body.get("status").asInt());
        assertFalse(body.get("retryable").asBoolean());
        assertNotNull(body.get("traceId"));
        assertFalse(response.body().contains("NoResourceFoundException"));
    }

    @Test
    void identityEndpointsAreClosedWithoutExplicitProfile() throws Exception {
        assertEquals(404, get("/api/v1/me").statusCode());
        assertEquals(404, get("/api/v1/auth/csrf").statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
