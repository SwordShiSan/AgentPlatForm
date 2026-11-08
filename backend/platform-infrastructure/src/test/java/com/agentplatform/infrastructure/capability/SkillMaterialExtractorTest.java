package com.agentplatform.infrastructure.capability;

import static org.junit.jupiter.api.Assertions.*;

import com.agentplatform.domain.capability.CapabilityMaterialization;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class SkillMaterialExtractorTest {
    @TempDir Path temporary;

    @Test void extractsExactlyTheFrozenFilesAndCleansOnRevocation() throws Exception {
        Path parent = Files.createDirectory(temporary.resolve("private"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path archive = temporary.resolve("skill.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (var entry : java.util.Map.of("wrapper/SKILL.md", "---\nname: sample\ndescription: sample\n---\nbody\n",
                    "wrapper/data/info.txt", "fixed-content").entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        var validator = new SkillArchiveValidator(); var found = validator.validate(archive);
        var version = new CapabilityMaterialization.Version(UUID.randomUUID(), UUID.randomUUID(), found.archiveSha256(),
                found.files().stream().map(file -> new CapabilityMaterialization.File(file.path(), file.bytes(), file.sha256())).toList());
        var extractor = new SkillMaterialExtractor(new SkillMaterializationVerifier(validator));
        Path material = extractor.extract(archive, version, parent, () -> {});
        assertEquals("fixed-content", Files.readString(material.resolve("data/info.txt")));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(material));
        var calls = new AtomicInteger();
        assertThrows(SecurityException.class, () -> extractor.extract(archive, version, parent, () -> {
            if (calls.incrementAndGet() >= 2) throw new SecurityException("revoked");
        }));
        try (var paths = Files.list(parent)) { assertEquals(1, paths.count(), "failed extraction must be removed"); }
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxr-x---"));
        assertThrows(IllegalArgumentException.class, () -> extractor.extract(archive, version, parent, () -> {}));
    }
}
