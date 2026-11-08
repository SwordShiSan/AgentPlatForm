package com.agentplatform.domain.capability;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapabilityMaterializationTest {
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    @Test void canonicalHashBindsIdentityAndContentsRegardlessOfInputOrder() {
        var space = UUID.randomUUID();
        var task = UUID.randomUUID();
        var id = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var a = new CapabilityMaterialization.File("a", 1, HASH);
        var b = new CapabilityMaterialization.File("dir/b", 2, OTHER_HASH);
        var first = new CapabilityMaterialization.Version(id, versionId, HASH, List.of(a, b));
        var second = new CapabilityMaterialization.Version(id, versionId, HASH, List.of(b, a));
        var manifest = new CapabilityMaterialization.Manifest(space, task, List.of(first));
        assertEquals(manifest.hash(), new CapabilityMaterialization.Manifest(space, task, List.of(second)).hash());
        assertNotEquals(manifest.hash(), new CapabilityMaterialization.Manifest(space, UUID.randomUUID(), List.of(first)).hash());
        assertNotEquals(manifest.hash(), new CapabilityMaterialization.Manifest(space, task, List.of(
                new CapabilityMaterialization.Version(id, versionId, OTHER_HASH, List.of(a, b)))).hash());
        assertEquals("/capabilities/" + id + "/" + versionId, first.mountTarget());
    }

    @Test void rejectsEscapesConflictsAndUnboundedMaterial() {
        assertThrows(IllegalArgumentException.class, () -> new CapabilityMaterialization.File("../file", 1, HASH));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityMaterialization.File("file", 1, "invalid"));
        var a = new CapabilityMaterialization.File("a", 1, HASH);
        var child = new CapabilityMaterialization.File("a/child", 1, HASH);
        assertThrows(IllegalArgumentException.class, () -> new CapabilityMaterialization.Version(
                UUID.randomUUID(), UUID.randomUUID(), HASH, List.of(a, child)));
        var tooLarge = new CapabilityMaterialization.File("large", CapabilityMaterialization.MAX_BYTES, HASH);
        var version = new CapabilityMaterialization.Version(UUID.randomUUID(), UUID.randomUUID(), HASH, List.of(a, tooLarge));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityMaterialization.Manifest(
                UUID.randomUUID(), UUID.randomUUID(), List.of(version)));
    }
}
