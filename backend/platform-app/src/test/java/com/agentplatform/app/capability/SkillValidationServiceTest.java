package com.agentplatform.app.capability;

import static org.junit.jupiter.api.Assertions.*;
import com.agentplatform.domain.capability.SkillValidationRepository;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.infrastructure.capability.SkillArchiveValidator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillValidationServiceTest {
    @TempDir Path temp;
    static class Repository implements SkillValidationRepository {
        int guards; Manifest manifest; String rejected, unknown;
        @Override public void guard(Command command) { guards++; }
        @Override public void draft(Command command, Manifest manifest) { this.manifest = manifest; }
        @Override public void rejected(Command command, String reason) { rejected = reason; }
        @Override public void unknown(Command command, String reason) { unknown = reason; }
        @Override public Publication nextPublication(UUID owner) { throw new UnsupportedOperationException(); }
        @Override public void published(Publication publication) { throw new UnsupportedOperationException(); }
        @Override public Command claim(Publication publication, UUID owner) { throw new UnsupportedOperationException(); }
        @Override public void quarantine(UUID id, String hash) { throw new UnsupportedOperationException(); }
    }
    static final class Store implements AssetStore {
        final byte[] bytes; boolean closed;
        Store(byte[] bytes) { this.bytes = bytes; }
        @Override public Content open(String key, Long first, Long last) {
            return new Content(new ByteArrayInputStream(bytes) {
                @Override public void close() throws java.io.IOException { super.close(); closed = true; }
            }, bytes.length, bytes.length);
        }
        @Override public Identity put(String key, long size, String sha, InputStream in) { throw new UnsupportedOperationException(); }
        @Override public Identity inspect(String key) { throw new UnsupportedOperationException(); }
        @Override public void deleteUncommitted(String key) { throw new UnsupportedOperationException(); }
    }
    private static byte[] archive(String name) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write("---\nname: test\ndescription: test description\n---\nbody\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    private static SkillValidationRepository.Command command(byte[] bytes, String hash) {
        return new SkillValidationRepository.Command(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, 1, "private", bytes.length, hash);
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    @Test void validatesOnlyAfterPrivateStreamIsClosed() throws Exception {
        byte[] bytes = archive("SKILL.md"); var store = new Store(bytes); var repo = new Repository() {
            @Override public void draft(Command command, Manifest manifest) { assertTrue(store.closed); super.draft(command, manifest); }
        };
        new SkillValidationService(repo, store, new SkillArchiveValidator(), temp).validate(command(bytes, hash(bytes)));
        assertNotNull(repo.manifest); assertEquals("test", repo.manifest.name()); assertTrue(repo.guards >= 2);
        assertNull(repo.unknown); assertNull(repo.rejected);
        try (var files = java.nio.file.Files.list(temp)) { assertEquals(0, files.count()); }
    }
    @Test void badArchiveIsRejectedAndHashMismatchRemainsUnknown() throws Exception {
        byte[] invalid = archive("../SKILL.md"); var repo = new Repository();
        new SkillValidationService(repo, new Store(invalid), new SkillArchiveValidator(), temp).validate(command(invalid, hash(invalid)));
        assertEquals("SKILL_ARCHIVE_INVALID", repo.rejected); assertNull(repo.manifest);
        byte[] valid = archive("SKILL.md"); var mismatch = new Repository();
        new SkillValidationService(mismatch, new Store(valid), new SkillArchiveValidator(), temp).validate(command(valid, "0".repeat(64)));
        assertEquals("SKILL_IMPORT_RESULT_UNKNOWN", mismatch.unknown); assertNull(mismatch.manifest);
        byte[] corrupt = "not a zip".getBytes(StandardCharsets.UTF_8); var broken = new Repository();
        new SkillValidationService(broken, new Store(corrupt), new SkillArchiveValidator(), temp).validate(command(corrupt, hash(corrupt)));
        assertEquals("SKILL_ARCHIVE_INVALID", broken.rejected);
    }
}
