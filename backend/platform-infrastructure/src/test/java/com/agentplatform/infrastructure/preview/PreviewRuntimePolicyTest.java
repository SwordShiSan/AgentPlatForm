package com.agentplatform.infrastructure.preview;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PreviewRuntimePolicyTest {
    private static final String IMAGE="sha256:"+"a".repeat(64);

    @Test void acceptsProtectedR63AcceptanceStagingRoot() {
        var policy=new PreviewRuntimePolicy(IMAGE,Path.of("/srv/agentplatform/acceptance/r63-20261108-linux/previews"),
                Path.of("/run/attestation.json"),"r63-v24");
        assertNotNull(policy.hash());
    }

    @Test void rejectsUnboundedOrLocalStagingRoots() {
        assertThrows(IllegalArgumentException.class,()->new PreviewRuntimePolicy(IMAGE,Path.of("/tmp/r63/previews"),
                Path.of("/run/attestation.json"),"r63-v24"));
        assertThrows(IllegalArgumentException.class,()->new PreviewRuntimePolicy(IMAGE,Path.of("/srv/agentplatform/acceptance/r63-*/previews"),
                Path.of("/run/attestation.json"),"r63-v24"));
    }
}
