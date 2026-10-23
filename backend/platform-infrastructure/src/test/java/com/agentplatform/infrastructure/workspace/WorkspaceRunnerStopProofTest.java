package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.workspace.WorkspaceFailure;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.json.JsonMapper;

class WorkspaceRunnerStopProofTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Instant NOW=Instant.parse("2026-10-22T00:02:00Z");
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private static final byte[] KEY=new byte[32];
    private final UUID runner=UUID.randomUUID();
    private WorkspaceRunnerStopProof.Payload payload(String scope,String start,String stop,String observed) {
        return new WorkspaceRunnerStopProof.Payload(scope,runner,"c".repeat(64),"sha256:"+"d".repeat(64),start,stop,observed);
    }
    private byte[] sign(Object payload) throws Exception {
        String text=JSON.writeValueAsString(payload);var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(KEY,"HmacSHA256"));
        return JSON.writeValueAsBytes(new WorkspaceRunnerStopProof.Envelope(text,HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)))));
    }
    @Test void exactRunnerStartAndPayloadHashArePreservedWithoutTaskFields() throws Exception {
        var payload=payload("WORKSPACE_ENVIRONMENT_V2","2026-10-22T00:00:00.123456789Z","2026-10-22T00:01:00Z",NOW.toString());
        var proof=WorkspaceRunnerStopProof.verify(sign(payload),KEY,CLOCK);
        assertEquals(runner,proof.instance().id());assertEquals(payload.startedAt(),proof.instance().startedAt());
        assertEquals(RunnerSignatures.sha256(JSON.writeValueAsBytes(payload)),proof.evidenceHash());
    }
    @Test void staleFutureChronologyAndWrongScopeAreRejected() throws Exception {
        for(var payload:List.of(
                payload("CHAT","2026-10-22T00:00:00Z","2026-10-22T00:01:00Z",NOW.toString()),
                payload("WORKSPACE_ENVIRONMENT_V2","2026-10-21T23:00:00Z","2026-10-21T23:01:00Z","2026-10-21T23:02:00Z"),
                payload("WORKSPACE_ENVIRONMENT_V2",NOW.toString(),"2026-10-22T00:01:00Z",NOW.toString()),
                payload("WORKSPACE_ENVIRONMENT_V2","2026-10-22T00:00:00Z","2026-10-22T00:01:00Z",NOW.plusSeconds(6).toString()))) {
            byte[] envelope=sign(payload);
            assertEquals("STOPPED_RUNNER_PROOF_INVALID",assertThrows(WorkspaceFailure.class,()->WorkspaceRunnerStopProof.verify(envelope,KEY,CLOCK)).code());
        }
    }
    @Test void tamperUnknownFieldsWrongKeyAndOversizeAreRejected() throws Exception {
        var payload=payload("WORKSPACE_ENVIRONMENT_V2","2026-10-22T00:00:00Z","2026-10-22T00:01:00Z",NOW.toString());
        byte[] signed=sign(payload),wrong=KEY.clone();wrong[0]=1;
        assertThrows(WorkspaceFailure.class,()->WorkspaceRunnerStopProof.verify(signed,wrong,CLOCK));
        byte[] tampered=new String(signed,StandardCharsets.UTF_8).replace("WORKSPACE_ENVIRONMENT_V2","WORKSPACE_ENVIRONMENT_V3").getBytes(StandardCharsets.UTF_8);
        assertThrows(WorkspaceFailure.class,()->WorkspaceRunnerStopProof.verify(tampered,KEY,CLOCK));
        byte[] unknown=sign(Map.of("scope","WORKSPACE_ENVIRONMENT_V2","taskId",UUID.randomUUID()));
        assertThrows(WorkspaceFailure.class,()->WorkspaceRunnerStopProof.verify(unknown,KEY,CLOCK));
        assertThrows(WorkspaceFailure.class,()->WorkspaceRunnerStopProof.verify(new byte[8193],KEY,CLOCK));
    }
}
