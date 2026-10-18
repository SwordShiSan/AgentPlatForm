package com.agentplatform.infrastructure.chat;

import static org.junit.jupiter.api.Assertions.*;
import com.agentplatform.domain.chat.ChatFailure;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StoppedOwnerProofTest {
    @Test void signatureBindsExactContainerOwnerAndTimes() throws Exception {
        String payload="{\"ownerId\":\"00000000-0000-4000-8000-000000000001\",\"taskId\":\"00000000-0000-4000-8000-000000000002\",\"attemptId\":\"00000000-0000-4000-8000-000000000003\",\"fence\":7,\"containerId\":\""+"a".repeat(64)+"\",\"imageId\":\"sha256:"+"b".repeat(64)+"\",\"startedAt\":\"2026-10-18T00:00:00Z\",\"stoppedAt\":\"2026-10-18T00:01:00Z\",\"observedAt\":\"2026-10-18T00:01:01Z\"}";
        byte[] key=new byte[32];Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
        String signature=HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        var mapper=JsonMapper.builder().build();
        byte[] envelope=mapper.writeValueAsBytes(Map.of("payload",payload,"signature",signature));
        assertEquals(7,StoppedOwnerProof.verify(envelope,key).fence());
        var changed=mapper.writeValueAsBytes(Map.of("payload",payload.replace("\"fence\":7","\"fence\":8"),"signature",signature));
        assertEquals("STOPPED_OWNER_PROOF_INVALID",assertThrows(ChatFailure.class,()->StoppedOwnerProof.verify(changed,key)).code());
        assertThrows(ChatFailure.class,()->StoppedOwnerProof.verify(envelope,new byte[31]));
        assertThrows(ChatFailure.class,()->StoppedOwnerProof.verify("{}".getBytes(StandardCharsets.UTF_8),key));
    }
}
