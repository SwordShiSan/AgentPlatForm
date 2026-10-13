package com.agentplatform.app;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthHttpTest {
    @LocalServerPort
    private int port;

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

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
