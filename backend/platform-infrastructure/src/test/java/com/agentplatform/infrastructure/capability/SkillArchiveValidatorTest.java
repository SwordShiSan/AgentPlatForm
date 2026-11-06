package com.agentplatform.infrastructure.capability;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.AsiExtraField;

class SkillArchiveValidatorTest {
    @TempDir Path temp;
    private static final String SKILL = "---\nname: test-skill\ndescription: A test skill\n---\nUse the resource.\n";

    private Path archive(Map<String, byte[]> entries) throws Exception {
        Path file = temp.resolve("test.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @Test void validatesWrappedSkillAndHashesActualContent() throws Exception {
        var manifest = new SkillArchiveValidator().validate(archive(Map.of(
                "package/SKILL.md", bytes(SKILL), "package/data.txt", bytes("hello"))));
        assertEquals("test-skill", manifest.name());
        assertEquals(2, manifest.files().size());
        assertEquals("data.txt", manifest.files().get(1).path());
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", manifest.files().get(1).sha256());
    }

    @Test void rejectsTraversalAndUnicodeCollisions() throws Exception {
        var validator = new SkillArchiveValidator();
        assertThrows(IllegalArgumentException.class, () -> validator.validate(archive(Map.of(
                "SKILL.md", bytes(SKILL), "../outside", bytes("x")))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(archive(Map.of(
                "SKILL.md", bytes(SKILL), "é.txt", bytes("a"), "e\u0301.txt", bytes("b")))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(archive(Map.of(
                "SKILL.md", bytes(SKILL), "x".repeat(256), bytes("a")))));
    }

    @Test void rejectsMissingMetadataAndInvalidUtf8() throws Exception {
        var validator = new SkillArchiveValidator();
        assertThrows(IllegalArgumentException.class, () -> validator.validate(archive(Map.of("SKILL.md", bytes("no metadata")))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(archive(Map.of("SKILL.md", new byte[]{(byte) 0xff}))));
    }

    @Test void rejectsFileUsedAsParentDirectory() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new SkillArchiveValidator().validate(archive(Map.of(
                "SKILL.md", bytes(SKILL), "resource", bytes("file"), "resource/child", bytes("child")))));
    }

    @Test void rejectsUnixSymlinkAndTooManyFiles() throws Exception {
        Path file = temp.resolve("links.zip");
        try (var zip = new ZipArchiveOutputStream(file)) {
            var skill = new ZipArchiveEntry("SKILL.md");
            zip.putArchiveEntry(skill);
            zip.write(bytes(SKILL));
            zip.closeArchiveEntry();
            var link = new ZipArchiveEntry("shortcut");
            link.setUnixMode(0120777);
            zip.putArchiveEntry(link);
            zip.write(bytes("SKILL.md"));
            zip.closeArchiveEntry();
        }
        var validator = new SkillArchiveValidator();
        assertThrows(IllegalArgumentException.class, () -> validator.validate(file));
        var entries = new java.util.HashMap<String, byte[]>();
        entries.put("SKILL.md", bytes(SKILL));
        for (int i = 0; i < 1000; i++) entries.put("f" + i, new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(archive(entries)));
    }

    @Test void rejectsLinkMetadataInUnixExtraField() throws Exception {
        Path file = temp.resolve("extra-link.zip");
        try (var zip = new ZipArchiveOutputStream(file)) {
            var skill = new ZipArchiveEntry("SKILL.md");
            zip.putArchiveEntry(skill);
            zip.write(bytes(SKILL));
            zip.closeArchiveEntry();
            var entry = new ZipArchiveEntry("shortcut");
            var unix = new AsiExtraField();
            unix.setLinkedFile("SKILL.md");
            entry.addExtraField(unix);
            zip.putArchiveEntry(entry);
            zip.write(bytes("SKILL.md"));
            zip.closeArchiveEntry();
        }
        assertThrows(IllegalArgumentException.class, () -> new SkillArchiveValidator().validate(file));
    }
}
