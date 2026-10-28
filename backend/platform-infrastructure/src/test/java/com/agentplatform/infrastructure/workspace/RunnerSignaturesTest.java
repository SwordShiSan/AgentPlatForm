package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.workspace.WorkspaceFailure;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class RunnerSignaturesTest {
    private static RSAKey control, runner;
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RunnerSignatures.Binding binding = new RunnerSignatures.Binding("POST", "/internal/operations",
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 7);
    @BeforeAll static void keys() throws Exception {
        control = new RSAKeyGenerator(3072).keyID("control-1").generate();
        runner = new RSAKeyGenerator(3072).keyID("runner-1").generate();
    }
    private RunnerSignatures control(Clock clock) { return new RunnerSignatures(control, runner.toPublicJWK(), "control", "runner", clock); }
    private RunnerSignatures runner(Clock clock) { return new RunnerSignatures(runner, control.toPublicJWK(), "runner", "control", clock); }
    @Test void exactRequestAndIndependentResponseVerify() {
        byte[] body = "{\"kind\":\"PROVISION\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        runner(CLOCK).verify(control(CLOCK).sign(binding, body), binding, body);
        var response = new RunnerSignatures.Binding("RESPONSE", binding.path(), binding.commandId(), binding.operationId(),
                binding.spaceId(), binding.workspaceId(), 1, 7);
        control(CLOCK).verify(runner(CLOCK).sign(response, body), response, body);
        assertThrows(WorkspaceFailure.class, () -> runner(CLOCK).verify(runner(CLOCK).sign(response, body), response, body));
    }
    @Test void tamperedBodyAndRouteAndFenceReject() {
        byte[] body = "{}".getBytes(); String token = control(CLOCK).sign(binding, body);
        assertThrows(WorkspaceFailure.class, () -> runner(CLOCK).verify(token, binding, "{ }".getBytes()));
        var stale = new RunnerSignatures.Binding("POST", binding.path(), binding.commandId(), binding.operationId(), binding.spaceId(), binding.workspaceId(), 1, 8);
        assertThrows(WorkspaceFailure.class, () -> runner(CLOCK).verify(token, stale, body));
        var get = new RunnerSignatures.Binding("GET", binding.path(), binding.commandId(), binding.operationId(), binding.spaceId(), binding.workspaceId(), 1, 7);
        assertThrows(WorkspaceFailure.class, () -> runner(CLOCK).verify(token, get, body));
    }
    @Test void expiredWrongPeerAndMissingTokensReject() throws Exception {
        String token = control(CLOCK).sign(binding, new byte[0]);
        assertThrows(WorkspaceFailure.class, () -> runner(Clock.fixed(NOW.plusSeconds(66), ZoneOffset.UTC)).authenticate(token));
        var other = new RSAKeyGenerator(3072).keyID("control-1").generate();
        var wrong = new RunnerSignatures(runner, other.toPublicJWK(), "runner", "control", CLOCK);
        assertThrows(WorkspaceFailure.class, () -> wrong.authenticate(token));
        assertThrows(WorkspaceFailure.class, () -> runner(CLOCK).authenticate(null));
    }
    @Test void oversizeAndInvalidScopeFailBeforeSigning() {
        assertThrows(IllegalArgumentException.class,()->new RunnerSignatures(control,control.toPublicJWK(),"control","runner",CLOCK));
        assertThrows(WorkspaceFailure.class, () -> control(CLOCK).sign(binding, new byte[65537]));
        assertThrows(IllegalArgumentException.class, () -> new RunnerSignatures.Binding("POST", "/outside", binding.commandId(), binding.operationId(), binding.spaceId(), binding.workspaceId(), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RunnerSignatures.Binding("GET", "/internal/operations-evil", binding.commandId(), binding.operationId(), binding.spaceId(), binding.workspaceId(), 1, 1));
    }
    @Test void wrongIssuerAudienceAndFutureTokensReject() {
        var wrongIssuer=new RunnerSignatures(control,runner.toPublicJWK(),"foreign-control","runner",CLOCK);
        var wrongAudience=new RunnerSignatures(control,runner.toPublicJWK(),"control","foreign-runner",CLOCK);
        assertThrows(WorkspaceFailure.class,()->runner(CLOCK).authenticate(wrongIssuer.sign(binding,new byte[0])));
        assertThrows(WorkspaceFailure.class,()->runner(CLOCK).authenticate(wrongAudience.sign(binding,new byte[0])));
        assertThrows(WorkspaceFailure.class,()->runner(CLOCK).authenticate(control(Clock.offset(CLOCK,Duration.ofSeconds(10))).sign(binding,new byte[0])));
    }
    @Test void shellEnvelopeBoundIsVersionAndDirectionSpecific() {
        var shell=new RunnerSignatures.Binding("POST","/internal/workspace-shell/v1",binding.commandId(),binding.operationId(),binding.spaceId(),binding.workspaceId(),1,7);
        byte[] escapedEnvelope=new byte[100000];
        runner(CLOCK).verify(control(CLOCK).sign(shell,escapedEnvelope),shell,escapedEnvelope);
        assertThrows(WorkspaceFailure.class,()->control(CLOCK).sign(shell,new byte[131073]));
        var response=new RunnerSignatures.Binding("RESPONSE",shell.path(),shell.commandId(),shell.operationId(),shell.spaceId(),shell.workspaceId(),1,7);
        assertThrows(WorkspaceFailure.class,()->runner(CLOCK).sign(response,new byte[65537]));
        assertThrows(WorkspaceFailure.class,()->runner(CLOCK).verify(control(CLOCK).sign(shell,escapedEnvelope),binding,escapedEnvelope));
        assertThrows(IllegalArgumentException.class,()->new RunnerSignatures.Binding("GET",shell.path()+"/"+UUID.randomUUID(),shell.commandId(),shell.operationId(),shell.spaceId(),shell.workspaceId(),1,7));
    }
    @Test void disallowedAlgorithmAndMissingLifetimeReject() throws Exception {
        var claims=new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer("control").audience("runner").jwtID(binding.commandId().toString()).build();
        var missingTime=new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
                .keyID("control-1").type(com.nimbusds.jose.JOSEObjectType.JWT).build(),claims);
        missingTime.sign(new com.nimbusds.jose.crypto.RSASSASigner(control));
        assertThrows(WorkspaceFailure.class,()->runner(CLOCK).authenticate(missingTime.serialize()));
        var wrongAlgorithm=new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS512)
                .keyID("control-1").type(com.nimbusds.jose.JOSEObjectType.JWT).build(),claims);
        wrongAlgorithm.sign(new com.nimbusds.jose.crypto.RSASSASigner(control));
        assertThrows(WorkspaceFailure.class,()->runner(CLOCK).authenticate(wrongAlgorithm.serialize()));
    }
    @Test void versionedFileCommandsAndQueriesRetainExactBodyAndOperationScope() {
        var file=new RunnerSignatures.Binding("POST","/internal/file-operations/v1",binding.commandId(),binding.operationId(),binding.spaceId(),binding.workspaceId(),1,7);
        byte[] body="{\"kind\":\"APPLY_UPLOAD\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        runner(CLOCK).verify(control(CLOCK).sign(file,body),file,body);
        var query=new RunnerSignatures.Binding("GET",file.path()+"/"+file.operationId(),file.commandId(),file.operationId(),file.spaceId(),file.workspaceId(),1,7);
        assertEquals(query,runner(CLOCK).verifiedQuery(control(CLOCK).sign(query,new byte[0]),query.path()));
        assertThrows(WorkspaceFailure.class,()->runner(CLOCK).verify(control(CLOCK).sign(file,body),file,"{ }".getBytes()));
        assertThrows(IllegalArgumentException.class,()->new RunnerSignatures.Binding("GET","/internal/file-operations/v1/"+UUID.randomUUID(),
                file.commandId(),file.operationId(),file.spaceId(),file.workspaceId(),1,7));
    }
}
