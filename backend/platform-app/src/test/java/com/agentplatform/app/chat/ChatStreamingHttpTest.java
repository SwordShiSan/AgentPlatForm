package com.agentplatform.app.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.api.identity.IdentityApi;
import com.agentplatform.trigger.http.ChatController;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;

@SpringBootTest(classes = ChatStreamingHttpTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=identity-local,chat-local,chat-stream-test", "server.shutdown=immediate"})
class ChatStreamingHttpTest {
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("sseExecutor") org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor;
    @org.springframework.beans.factory.annotation.Autowired com.agentplatform.api.chat.ChatEventStream streams;
    private static volatile LongFunction<List<ChatApi.EventView>> page = after -> List.of();
    private static final AtomicBoolean revoked = new AtomicBoolean();
    private static volatile long authorizationDelay;
    @Configuration(proxyBeanMethods = false)
    @Profile("chat-stream-test")
    @EnableAutoConfiguration
    @Import({ChatController.class, ChatWebConfiguration.class, com.agentplatform.trigger.http.ProblemHandler.class,
            com.agentplatform.trigger.http.HttpSecurityConfiguration.class, com.agentplatform.trigger.http.RequestTraceFilter.class})
    static class Config {
        @Bean ChatApi chat() {
            return (ChatApi) java.lang.reflect.Proxy.newProxyInstance(ChatApi.class.getClassLoader(), new Class<?>[]{ChatApi.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("events")) return page.apply((long) args[2]);
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
        @Bean IdentityApi identity() {
            var me = new IdentityApi.MeResponse(
                    "0199e000-0000-7000-8000-000000000001", "fixture", false,
                    List.of(new IdentityApi.SpaceResponse("0199e000-0000-7000-8000-000000000002", "PERSONAL", "OWNER", 0)));
            return (IdentityApi) java.lang.reflect.Proxy.newProxyInstance(IdentityApi.class.getClassLoader(), new Class<?>[]{IdentityApi.class},
                    (proxy, method, args) -> {
                        if (authorizationDelay > 0) Thread.sleep(authorizationDelay);
                        if (revoked.get()) throw new com.agentplatform.domain.identity.IdentityFailure(401, "AUTHENTICATION_REQUIRED");
                        if (method.getName().equals("me")) return me;
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    @Test void stoppedReaderReleasesAdmissionWithinThirtyFiveSeconds() throws Exception {
        awaitDisconnectedStreams();
        page = after -> java.util.stream.LongStream.rangeClosed(after + 1, after + 128)
                .mapToObj(seq -> new ChatApi.EventView(1, "event-" + seq, "space", "conversation", "task", seq,
                        "message.delta", "2026-10-20T00:00:00Z", seq, Map.of("text", "x".repeat(6000)))).toList();
        long started = System.nanoTime();
        try (var socket = new java.net.Socket()) {
            socket.setReceiveBufferSize(1024);
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2000);
            socket.getOutputStream().write(("GET /api/v1/conversations/0199e000-0000-7000-8000-000000000003/events?after=0 HTTP/1.1\r\n"
                    + "Host: localhost\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            long admissionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (streams.activeStreams() == 0 && System.nanoTime() < admissionDeadline) Thread.sleep(10);
            assertEquals(1, streams.activeStreams());
            long deadline = started + TimeUnit.SECONDS.toNanos(35);
            while ((streams.activeStreams() != 0 || executor.getActiveCount() != 0) && System.nanoTime() < deadline) Thread.sleep(50);
            assertEquals(0, streams.activeStreams(), "A client that never reads must not retain admission");
            assertEquals(0, executor.getActiveCount(), "Blocked writes must terminate within the transport deadline");
        } finally { page = after -> List.of(); }
    }

    @Test void authorizationRevocationClosesLiveStreamWithoutClientDemand() throws Exception {
        awaitDisconnectedStreams();
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/api/v1/conversations/0199e000-0000-7000-8000-000000000003/events?after=0")).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            revoked.set(true);
            try (var body = response.body()) { awaitDisconnectedStreams(); }
        } finally { revoked.set(false); }
    }

    @Test void delayedSessionRevalidationClosesCleanlyInsteadOfOverflowingTimerDemand() throws Exception {
        awaitDisconnectedStreams();
        try (var client=HttpClient.newHttpClient()) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port
                    +"/api/v1/conversations/0199e000-0000-7000-8000-000000000003/events?after=0")).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200,response.statusCode());
            authorizationDelay=3000;
            try(var body=response.body()) {
                var result=CompletableFuture.supplyAsync(()-> {
                    try {return new String(body.readAllBytes(),StandardCharsets.UTF_8);}
                    catch(Exception failure){throw new CompletionException(failure);}
                }).get(8,TimeUnit.SECONDS);
                assertTrue(result.startsWith(": connected"));
                assertFalse(result.contains("problem"));
                awaitDisconnectedStreams();
            }
        } finally {authorizationDelay=0;}
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

    private void awaitDisconnectedStreams() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(9);
        while(streams.activeStreams()!=0&&System.nanoTime()<deadline)Thread.sleep(50);
        assertEquals(0,streams.activeStreams(),"Disconnected sockets must release the bounded workers promptly");
    }

    @Test void eightStreamsStartWithoutQueueingAndExcessCapacityFailsExplicitly() throws Exception {
        awaitDisconnectedStreams();
        var responses=new java.util.ArrayList<HttpResponse<java.io.InputStream>>();
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/conversations/0199e000-0000-7000-8000-000000000003/events?after=0"))
                    .header("Accept","text/event-stream").timeout(Duration.ofSeconds(3)).build();
            try {
                for(int i=0;i<8;i++)responses.add(client.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream()).get(3,TimeUnit.SECONDS));
                assertTrue(responses.stream().allMatch(response->response.statusCode()==200));
                var rejected=client.sendAsync(request,HttpResponse.BodyHandlers.ofString()).get(3,TimeUnit.SECONDS);
                assertEquals(503,rejected.statusCode()); assertTrue(rejected.body().contains("STREAM_CAPACITY_EXCEEDED"));
                assertTrue(rejected.body().contains("\"retryable\":true"));
            } finally { for(var response:responses)response.body().close(); }
        }
        awaitDisconnectedStreams();
    }

    @Test void malformedCursorsAndOversizedPagesFailBeforeStreamingHeaders() throws Exception {
        awaitDisconnectedStreams();
        try (var client=HttpClient.newHttpClient()) {
            String path="http://127.0.0.1:"+port+"/api/v1/conversations/0199e000-0000-7000-8000-000000000003/events";
            for(String cursor:List.of("bad","1.5","9007199254740992","9223372036854775808")) {
                var response=client.send(HttpRequest.newBuilder(URI.create(path+"?after="+cursor)).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(400,response.statusCode()); assertTrue(response.body().contains("INVALID_CURSOR"));
            }
            var preferred=client.send(HttpRequest.newBuilder(URI.create(path+"?after=0")).header("Last-Event-ID","invalid").build(),HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200,preferred.statusCode()); preferred.body().close(); awaitDisconnectedStreams();
            page=after->java.util.Collections.nCopies(257,new ChatApi.EventView(1,"event","space","conversation",null,1,
                    "message.delta","2026-10-20T00:00:00Z",1,Map.of()));
            try {
                var response=client.send(HttpRequest.newBuilder(URI.create(path)).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(503,response.statusCode());assertTrue(response.body().contains("EVENT_STREAM_OVERSIZED"));
                assertFalse(response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));
                assertEquals(0,streams.activeStreams());
            } finally {page=after->List.of();}
        }
    }
}
