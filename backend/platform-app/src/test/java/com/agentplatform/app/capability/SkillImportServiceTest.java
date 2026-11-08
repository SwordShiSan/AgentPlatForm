package com.agentplatform.app.capability;

import static org.junit.jupiter.api.Assertions.*;
import com.agentplatform.domain.capability.SkillImportRepository;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.domain.files.FileFailure;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SkillImportServiceTest {
    private static final class Repository implements SkillImportRepository {
        final UUID id = UUID.randomUUID(), capability = UUID.randomUUID(), version = UUID.randomUUID(), asset = UUID.randomUUID();
        final String hash; String state = "CREATED"; int guards; UUID owner;
        Repository(String hash) { this.hash = hash; }
        private Upload row() { return new Upload(id, capability, version, asset, state, 3, hash, "private", 1, null); }
        @Override public Upload create(Actor a, UUID requestId, String requestHash, UUID capability, long bytes, String sha) { throw new UnsupportedOperationException(); }
        @Override public Upload find(Actor a, UUID id) { return row(); }
        @Override public Upload findRequest(Actor a, UUID request) { return row(); }
        @Override public Upload beginTransfer(Actor a, UUID id, UUID owner) { this.owner = owner; state = "UPLOADING"; return row(); }
        @Override public void guardTransfer(Actor a, UUID id, UUID owner) { assertEquals(this.owner, owner); guards++; }
        @Override public void verified(Actor a, UUID id, UUID owner) { state = "VERIFIED"; }
        @Override public void transferUnknown(Actor a, UUID id, UUID owner) { state = "BLOCKED"; }
        @Override public Upload enqueue(Actor a, UUID id, UUID request) { state = "QUEUED"; return row(); }
    }
    private static final class Store implements AssetStore {
        boolean failAfterRead;
        @Override public Identity put(String key, long bytes, String sha, InputStream input) {
            try { assertEquals("abc", new String(input.readAllBytes(), StandardCharsets.UTF_8)); }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
            if (failAfterRead) throw new FileFailure(503, "RESULT_UNKNOWN");
            return new Identity(key, bytes, sha);
        }
        @Override public Content open(String key, Long first, Long last) { throw new UnsupportedOperationException(); }
        @Override public Identity inspect(String key) { throw new UnsupportedOperationException(); }
        @Override public void deleteUncommitted(String key) { throw new UnsupportedOperationException(); }
    }
    private static String sha() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("abc".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void streamsAndQueuesOnlyAfterVerified() throws Exception {
        var repo = new Repository(sha()); var store = new Store(); var service = new SkillImportService(repo, store);
        var actor = new SkillImportRepository.Actor(UUID.randomUUID(), UUID.randomUUID());
        assertEquals("VERIFIED", service.transfer(actor, repo.id, 3, new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8))).state());
        assertTrue(repo.guards > 0);
        assertEquals("QUEUED", service.complete(actor, repo.id, UUID.randomUUID()).state());
    }
    @Test void uncertainWriteIsBlockedInsteadOfBeingAssumedUnsent() throws Exception {
        var repo = new Repository(sha()); var store = new Store(); store.failAfterRead = true;
        var service = new SkillImportService(repo, store); var actor = new SkillImportRepository.Actor(UUID.randomUUID(), UUID.randomUUID());
        assertThrows(FileFailure.class, () -> service.transfer(actor, repo.id, 3, new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8))));
        assertEquals("BLOCKED", repo.state);
    }
}
