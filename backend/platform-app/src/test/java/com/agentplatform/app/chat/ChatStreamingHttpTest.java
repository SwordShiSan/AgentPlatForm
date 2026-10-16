package com.agentplatform.app.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.api.identity.IdentityApi;
import com.agentplatform.trigger.http.ChatController;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;

@SpringBootTest(classes = ChatStreamingHttpTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=chat-local,chat-stream-test", "server.shutdown=immediate"})
class ChatStreamingHttpTest {
    @LocalServerPort int port;
    @Configuration(proxyBeanMethods = false)
    @Profile("chat-stream-test")
    @EnableAutoConfiguration
    @Import(ChatController.class)
    static class Config {
        @Bean ChatApi chat() {
            var chat = mock(ChatApi.class);
            when(chat.events(any(), anyString(), anyLong())).thenReturn(List.of());
            return chat;
        }
        @Bean IdentityApi identity() {
            var identity = mock(IdentityApi.class);
            when(identity.me(any(), any())).thenReturn(new IdentityApi.MeResponse(
                    "0199e000-0000-7000-8000-000000000001", "fixture", false,
                    List.of(new IdentityApi.SpaceResponse("0199e000-0000-7000-8000-000000000002", "PERSONAL", "OWNER", 0))));
            return identity;
        }
    }

    @Test void sendsEventStreamHeadersAndFirstFrameBeforeLongPollCompletes() throws Exception {
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/conversations/0199e000-0000-7000-8000-000000000003/events?after=0"))
                    .header("Accept","text/event-stream").timeout(Duration.ofSeconds(3)).build();
            var response=client.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream()).get(3,TimeUnit.SECONDS);
            assertEquals(200,response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
            assertEquals("no",response.headers().firstValue("X-Accel-Buffering").orElse(""));
            try(var body=response.body()) {
                var first=CompletableFuture.supplyAsync(()->{
                    try{return new String(body.readNBytes(13),StandardCharsets.UTF_8);}catch(Exception error){throw new CompletionException(error);}
                });
                assertEquals(": connected\n\n",first.get(3,TimeUnit.SECONDS));
            }
        }
    }
}
