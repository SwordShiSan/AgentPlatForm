package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.workspace.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Offline operator only. No application process is given this independent host key. */
public final class WorkspaceRunnerStopProof {
    private static final JsonMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private WorkspaceRunnerStopProof() {}
    public record Envelope(String payload,String signature) {}
    public record Payload(String scope,UUID runnerInstanceId,String containerId,String imageId,String startedAt,String stoppedAt,String observedAt) {}
    public static StoppedRunner verify(byte[] envelope,byte[] key,Clock clock) {
        try {
            if(key==null||key.length!=32||envelope==null||envelope.length>8192)throw new IllegalArgumentException();
            var signed=JSON.readValue(envelope,Envelope.class);
            if(signed==null||signed.payload()==null||signed.signature()==null||!signed.signature().matches("[0-9a-f]{64}"))throw new IllegalArgumentException();
            byte[] payload=signed.payload().getBytes(StandardCharsets.UTF_8);
            if(payload.length>4096)throw new IllegalArgumentException();
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            if(!MessageDigest.isEqual(mac.doFinal(payload),HexFormat.of().parseHex(signed.signature())))throw new IllegalArgumentException();
            var fields=JSON.readValue(payload,Payload.class);
            if(fields==null||!"WORKSPACE_ENVIRONMENT_V2".equals(fields.scope()))throw new IllegalArgumentException();
            var instance=new RunnerInstance(fields.runnerInstanceId(),fields.containerId(),fields.imageId(),fields.startedAt());
            var result=new StoppedRunner(instance,Instant.parse(fields.stoppedAt()),Instant.parse(fields.observedAt()),RunnerSignatures.sha256(payload));
            if(result.observedAt().isAfter(clock.instant().plusSeconds(5))||result.observedAt().isBefore(clock.instant().minusSeconds(300)))throw new IllegalArgumentException();
            return result;
        } catch(Exception invalid) {throw new WorkspaceFailure(409,"STOPPED_RUNNER_PROOF_INVALID");}
    }
}
