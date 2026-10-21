package com.agentplatform.app.workspace;

import com.agentplatform.api.workspace.RunnerControlApi;
import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.WorkspaceData.*;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import com.agentplatform.trigger.http.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP/security/JOSE with deterministic repository and allocator; native effects are tested separately. */
@SpringBootTest(classes=RunnerHttpTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.profiles.active=workspace-runner,runner-http-test","server.shutdown=immediate"})
class RunnerHttpTest {
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired WorkspaceRepository repository;
    @org.springframework.beans.factory.annotation.Autowired WorkspaceAllocator allocator;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("control") RunnerSignatures control;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final Command command=new Command(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
            UUID.randomUUID(),1,1,"PROVISION",2147483648L,100000,200001,Instant.now().plusSeconds(120));
    @Configuration(proxyBeanMethods=false) @Profile("runner-http-test") @EnableAutoConfiguration
    @Import({RunnerController.class,RunnerSecurityConfiguration.class,ProblemHandler.class,RequestTraceFilter.class})
    static class Config {
        record Peers(RunnerSignatures control,RunnerSignatures runner) {}
        @Bean WorkspaceRepository repository(){return mock(WorkspaceRepository.class);}
        @Bean WorkspaceAllocator allocator(){return mock(WorkspaceAllocator.class);}
        @Bean Peers peers() throws Exception {
            var control=new RSAKeyGenerator(3072).keyID("control-test").generate();var runner=new RSAKeyGenerator(3072).keyID("runner-test").generate();
            return new Peers(new RunnerSignatures(control,runner.toPublicJWK(),"control","runner",Clock.systemUTC()),
                    new RunnerSignatures(runner,control.toPublicJWK(),"runner","control",Clock.systemUTC()));
        }
        @Bean("control") RunnerSignatures control(Peers peers){return peers.control();}
        @Bean("runner") RunnerSignatures runner(Peers peers){return peers.runner();}
        @Bean JwtDecoder decoder(@org.springframework.beans.factory.annotation.Qualifier("runner") RunnerSignatures signatures){return signatures.resourceServerDecoder();}
        @Bean RunnerControlApi runnerApi(WorkspaceRepository repository,WorkspaceAllocator allocator,
                @org.springframework.beans.factory.annotation.Qualifier("runner") RunnerSignatures signatures){return new RunnerControlService(repository,signatures,allocator);}
    }
    @BeforeEach void resetPorts() {
        reset(repository,allocator);when(repository.lookup(command.operationId())).thenReturn(command);
        when(allocator.provision(command)).thenReturn("a".repeat(64));
    }
    private HttpResponse<byte[]> post(String token,byte[] body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/internal/operations"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        try(var client=HttpClient.newHttpClient()){return client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray());}
    }
    @Test void acceptedRequestReturnsIndependentlyVerifiableReceiptAndReplayDoesNotAllocate() throws Exception {
        byte[] body=JSON.writeValueAsBytes(command);String token=control.sign(RunnerControlService.binding(command,"POST","/internal/operations"),body);
        var response=post(token,body);assertEquals(200,response.statusCode());
        control.verify(response.headers().firstValue("X-Runner-Signature").orElseThrow(),RunnerControlService.binding(command,"RESPONSE","/internal/operations"),response.body());
        var receipt=JSON.readValue(response.body(),Receipt.class);assertEquals("COMMITTED",receipt.state());
        when(repository.accept(command,RunnerSignatures.sha256(body))).thenReturn(receipt);
        assertEquals(200,post(token,body).statusCode());verify(allocator,times(1)).provision(command);
        verify(repository,times(1)).commitReceipt(eq(command),eq(receipt),anyString(),anyString());
    }
    @Test void missingForgedAndTamperedRequestsNeverReachAllocator() throws Exception {
        byte[] body=JSON.writeValueAsBytes(command);String token=control.sign(RunnerControlService.binding(command,"POST","/internal/operations"),body);
        assertEquals(401,post(null,body).statusCode());assertEquals(401,post("invalid",body).statusCode());
        byte[] changed=(new String(body,java.nio.charset.StandardCharsets.UTF_8)+" ").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(401,post(token,changed).statusCode());verifyNoInteractions(allocator);verifyNoInteractions(repository);
    }
    @Test void oversizeAuthenticatedBodyIsRejectedBeforeBindingOrEffects() throws Exception {
        byte[] body=JSON.writeValueAsBytes(command);String token=control.sign(RunnerControlService.binding(command,"POST","/internal/operations"),body);
        assertEquals(413,post(token,new byte[65537]).statusCode());verifyNoInteractions(repository,allocator);
    }
    @Test void signedButIncompleteCommandFailsValidationBeforeAnyRepositoryOrEffect() throws Exception {
        byte[] body="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String token=control.sign(RunnerControlService.binding(command,"POST","/internal/operations"),body);
        assertEquals(400,post(token,body).statusCode());verifyNoInteractions(repository,allocator);
    }
    @Test void cleanupFailureCannotEraseAnAlreadyCommittedReceipt() throws Exception {
        doThrow(new IllegalStateException("Injected helper cleanup unavailable")).when(allocator).releaseCompletedHelper(command);
        byte[] body=JSON.writeValueAsBytes(command);String token=control.sign(RunnerControlService.binding(command,"POST","/internal/operations"),body);
        var response=post(token,body);assertEquals(200,response.statusCode());assertEquals("COMMITTED",JSON.readValue(response.body(),Receipt.class).state());
        var order=inOrder(repository,allocator);order.verify(repository).commitReceipt(eq(command),any(Receipt.class),anyString(),anyString());
        order.verify(allocator).releaseCompletedHelper(command);
    }
}
