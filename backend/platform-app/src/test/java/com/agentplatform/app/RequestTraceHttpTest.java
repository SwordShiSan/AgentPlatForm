package com.agentplatform.app;

import static org.junit.jupiter.api.Assertions.*;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.trigger.http.ProblemHandler;
import com.agentplatform.trigger.http.RequestTraceFilter;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;

@SpringBootTest(classes=RequestTraceHttpTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestTraceHttpTest {
    @LocalServerPort int port;
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @Import({ProblemHandler.class,RequestTraceFilter.class,Endpoint.class})
    static class Config {}
    @RestController static class Endpoint {
        @GetMapping("/trace-test") Object fail() { throw new ChatFailure(403,"CREDENTIAL_REVOKED"); }
    }
    @Test void problemAndHeaderShareServerTraceAndDoNotTrustCallerHeader() throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/trace-test"))
                .header("X-Trace-Id","caller-controlled").build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(403,response.statusCode());
            String id=response.headers().firstValue("X-Trace-Id").orElseThrow();
            assertDoesNotThrow(()->java.util.UUID.fromString(id));
            assertTrue(response.body().contains(id));assertFalse(response.body().contains("caller-controlled"));
        }
    }
}
