package com.agentplatform.infrastructure.workspace;

import static org.junit.jupiter.api.Assertions.*;
import com.agentplatform.domain.workspace.SandboxRuntime;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SandboxPolicyTest {
    private static final String IMAGE = "sha256:" + "a".repeat(64);
    private static final String HELPER = "sha256:" + "b".repeat(64);
    private static final UUID MOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test void mutableImageAndArbitraryRootOrDeviceAreRejected() {
        for (String image : new String[]{"sandbox:latest", "sandbox:fixed", "", "sha256:bad"}) {
            assertThrows(IllegalArgumentException.class, () -> new SandboxPolicy(image, HELPER,
                    "/srv/agentplatform/acceptance/r32-local/workspaces", MOUNT, "/dev/loop1", Path.of("attestation.json")));
        }
        for (String root : new String[]{"/", "/home/user", "/srv/agentplatform/acceptance/r31-old/workspaces",
                "/srv/agentplatform/acceptance/r32-local/../workspaces"}) {
            assertThrows(IllegalArgumentException.class, () -> new SandboxPolicy(IMAGE, HELPER, root,
                    MOUNT, "/dev/loop1", Path.of("attestation.json")));
        }
        assertThrows(IllegalArgumentException.class, () -> new SandboxPolicy(IMAGE, HELPER,
                "/srv/agentplatform/acceptance/r32-local/workspaces", MOUNT, "", Path.of("attestation.json")));
    }

    @Test void policyDigestBindsImageHelperMountAndExactDevice() {
        var a = policy(HELPER, "/dev/loop1");
        assertEquals(a.hash(), policy(HELPER, "/dev/loop1").hash());
        assertNotEquals(a.hash(), policy(IMAGE, "/dev/loop1").hash());
        assertNotEquals(a.hash(), policy(HELPER, "/dev/loop2").hash());
        assertEquals(64, a.hash().length());
    }

    @Test void physicalPathUsesOnlyServerUuidIdentity() {
        var id = new SandboxRuntime.Identity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, 1, 200001);
        assertEquals("/srv/agentplatform/acceptance/r32-local/workspaces/" + id.spaceId() + "/" + id.workspaceId() + "/work",
                policy(HELPER, "/dev/loop1").work(id));
        assertThrows(IllegalArgumentException.class, () -> new SandboxRuntime.Identity(id.environmentId(),
                id.spaceId(), id.workspaceId(), id.commandId(), 0, 1, 200001));
    }

    private static SandboxPolicy policy(String helper, String device) {
        return new SandboxPolicy(IMAGE, helper, "/srv/agentplatform/acceptance/r32-local/workspaces",
                MOUNT, device, Path.of("attestation.json"));
    }
}
