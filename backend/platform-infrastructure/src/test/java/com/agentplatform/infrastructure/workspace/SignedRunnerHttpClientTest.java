package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.WorkspaceData.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SignedRunnerHttpClientTest {
    private HttpServer server;
    private ExecutorService executor;
    private RunnerSignatures signatures;
    private URI base;
    private final Command command=new Command(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,"PROVISION",2147483648L,100000,200001,Instant.now().plusSeconds(120));
    @BeforeEach void start() throws Exception {
        var control=new RSAKeyGenerator(3072).keyID("control").generate();var runner=new RSAKeyGenerator(3072).keyID("runner").generate();
        signatures=new RunnerSignatures(control,runner.toPublicJWK(),"control","runner",Clock.systemUTC());
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
    }
    @AfterEach void stop(){server.stop(0);executor.shutdownNow();}
    @Test void validLookingUnsignedReceiptIsNeverTrusted() {
        server.createContext("/internal/operations",exchange->{byte[] body="{\"state\":\"COMMITTED\"}".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try(var client=new SignedRunnerHttpClient(base,signatures)) {
            assertEquals("RUNNER_RESULT_UNAUTHENTICATED",assertThrows(WorkspaceFailure.class,()->client.provision(command)).code());
        }
    }
    @Test void responseBodyThatNeverCompletesHasATotalDeadline() {
        server.createContext("/internal/operations",exchange->{exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();
            try{Thread.sleep(5000);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
        long started=System.nanoTime();
        try(var client=new SignedRunnerHttpClient(base,signatures,Duration.ofMillis(500))) {assertThrows(WorkspaceFailure.class,()->client.provision(command));}
        assertTrue(Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(3))<0,"A stalled body must not retain the lifecycle worker indefinitely");
    }
    @Test void oversizedBodyCancelsBeforeUnboundedAccumulation() {
        server.createContext("/internal/operations",exchange->{byte[] body=new byte[65537];exchange.sendResponseHeaders(200,body.length);try{exchange.getResponseBody().write(body);}finally{exchange.close();}});server.start();
        try(var client=new SignedRunnerHttpClient(base,signatures)){assertThrows(WorkspaceFailure.class,()->client.provision(command));}
    }
}
