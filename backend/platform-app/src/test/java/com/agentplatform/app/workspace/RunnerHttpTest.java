package com.agentplatform.app.workspace;

import com.agentplatform.api.workspace.RunnerControlApi;
import com.agentplatform.api.workspace.EnvironmentRunnerApi;
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
        properties={"spring.profiles.active=workspace-runner,runner-http-test","server.shutdown=immediate","platform.workspace.environment.enabled=true"})
class RunnerHttpTest {
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired WorkspaceRepository repository;
    @org.springframework.beans.factory.annotation.Autowired WorkspaceAllocator allocator;
    @org.springframework.beans.factory.annotation.Autowired EnvironmentExecutionRepository environmentRepository;
    @org.springframework.beans.factory.annotation.Autowired SandboxRuntime sandbox;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("control") RunnerSignatures control;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final Command command=new Command(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
            UUID.randomUUID(),1,1,"PROVISION",2147483648L,100000,200001,Instant.now().plusSeconds(120));
    @Configuration(proxyBeanMethods=false) @Profile("runner-http-test") @EnableAutoConfiguration
    @Import({RunnerController.class,EnvironmentRunnerController.class,RunnerSecurityConfiguration.class,ProblemHandler.class,RequestTraceFilter.class})
    static class Config {
        record Peers(RunnerSignatures control,RunnerSignatures runner) {}
        @Bean WorkspaceRepository repository(){return mock(WorkspaceRepository.class);}
        @Bean WorkspaceAllocator allocator(){return mock(WorkspaceAllocator.class);}
        @Bean EnvironmentExecutionRepository environmentRepository(){return mock(EnvironmentExecutionRepository.class);}
        @Bean SandboxRuntime sandbox(){return mock(SandboxRuntime.class);}
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
        @Bean EnvironmentRunnerApi environmentApi(EnvironmentExecutionRepository repository,SandboxRuntime sandbox,
                @org.springframework.beans.factory.annotation.Qualifier("runner") RunnerSignatures signatures) {
            return new EnvironmentRunnerService(repository,signatures,sandbox,"sha256:"+"a".repeat(64),"b".repeat(64));
        }
    }
    @BeforeEach void resetPorts() {
        reset(repository,allocator,environmentRepository,sandbox);when(repository.lookup(command.operationId())).thenReturn(command);
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
    private com.agentplatform.domain.workspace.EnvironmentExecutionData.Command environmentCommand() {
        return new com.agentplatform.domain.workspace.EnvironmentExecutionData.Command("ENVIRONMENT_V2","PREPARE","USER",UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,UUID.randomUUID(),1,
                "sha256:"+"a".repeat(64),"b".repeat(64),200001,null,null,Instant.now().plusSeconds(120));
    }
    @Test void environmentV2UsesSameBoundedSignedClientAndDuplicateDoesNotCreate() {
        var command=environmentCommand();when(environmentRepository.lookup(command.operationId())).thenReturn(command);
        var ready=new SandboxRuntime.Container("c".repeat(64),command.imageId(),"2026-10-22T00:00:00Z",true,command.policyHash(),"d".repeat(64));
        when(sandbox.prepare(eq(command.physicalIdentity()),any())).thenReturn(ready);
        try(var client=new com.agentplatform.infrastructure.workspace.SignedRunnerHttpClient(URI.create("http://127.0.0.1:"+port),control)) {
            var result=client.prepareEnvironment(command);assertEquals("COMMITTED",result.state());assertEquals(ready,result.container());
            when(environmentRepository.accept(eq(command),anyString())).thenReturn(result);
            assertEquals(result,client.prepareEnvironment(command));
            verify(sandbox,times(1)).prepare(eq(command.physicalIdentity()),any());
            verify(environmentRepository,times(1)).commitReceipt(command,result);
            when(environmentRepository.receipt(command)).thenReturn(result);
            assertEquals(result,client.queryEnvironment(command));
        }
    }
    @Test void environmentTamperAndWrongQueryRouteAreRejectedBeforeLookup() throws Exception {
        var command=environmentCommand();String path=EnvironmentRunnerService.PATH;byte[] body=JSON.writeValueAsBytes(command);
        String token=control.sign(EnvironmentRunnerService.binding(command,"POST",path),body);
        try(var client=HttpClient.newHttpClient()) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json")
                    .header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.ofByteArray((new String(body,java.nio.charset.StandardCharsets.UTF_8)+" ").getBytes(java.nio.charset.StandardCharsets.UTF_8))).build();
            assertEquals(401,client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode());
            String queryToken=control.sign(EnvironmentRunnerService.binding(command,"GET",path+"/"+command.operationId()),new byte[0]);
            var wrong=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path+"/"+UUID.randomUUID()))
                    .header("Authorization","Bearer "+queryToken).GET().build();
            assertEquals(401,client.send(wrong,HttpResponse.BodyHandlers.discarding()).statusCode());
        }
        verifyNoInteractions(environmentRepository,sandbox);
    }
    @Test void environmentBodyBoundAndIncompleteCommandHaveNoEffects() throws Exception {
        var command=environmentCommand();String path=EnvironmentRunnerService.PATH;
        try(var client=HttpClient.newHttpClient()) {
            for(byte[] body:new byte[][]{new byte[16385],"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)}) {
                String token=control.sign(EnvironmentRunnerService.binding(command,"POST",path),body);
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json")
                        .header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                assertEquals(body.length>16384?413:400,client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode());
            }
        }
        verifyNoInteractions(environmentRepository,sandbox);
    }
}
