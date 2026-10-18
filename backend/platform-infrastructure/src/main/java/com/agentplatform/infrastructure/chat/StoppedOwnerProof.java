package com.agentplatform.infrastructure.chat;

import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.ExecutionLedger.StoppedOwner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.json.JsonMapper;

/** Only the offline operator receives this independent host-attestation key. */
public final class StoppedOwnerProof {
    private StoppedOwnerProof() {}
    public static StoppedOwner verify(byte[] envelope,byte[] key) {
        try {
            if(key.length!=32||envelope.length>8192)throw new IllegalArgumentException();
            var mapper=JsonMapper.builder().build();var value=mapper.readTree(envelope);
            if(value.size()!=2||!value.has("payload")||!value.has("signature"))throw new IllegalArgumentException();
            String payload=value.get("payload").asText(),signature=value.get("signature").asText();
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
            if(!MessageDigest.isEqual(mac.doFinal(bytes),HexFormat.of().parseHex(signature)))throw new IllegalArgumentException();
            @SuppressWarnings("unchecked") Map<String,Object> fields=mapper.readValue(payload,Map.class);
            if(!fields.keySet().equals(Set.of("ownerId","taskId","attemptId","fence","containerId","imageId","startedAt","stoppedAt","observedAt")))throw new IllegalArgumentException();
            return new StoppedOwner(UUID.fromString((String)fields.get("ownerId")),UUID.fromString((String)fields.get("taskId")),
                    UUID.fromString((String)fields.get("attemptId")),((Number)fields.get("fence")).longValue(),
                    (String)fields.get("containerId"),(String)fields.get("imageId"),Instant.parse((String)fields.get("startedAt")),
                    Instant.parse((String)fields.get("stoppedAt")),Instant.parse((String)fields.get("observedAt")),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }catch(Exception invalid){throw new ChatFailure(409,"STOPPED_OWNER_PROOF_INVALID");}
    }
}
