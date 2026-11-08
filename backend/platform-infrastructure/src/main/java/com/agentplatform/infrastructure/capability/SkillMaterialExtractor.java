package com.agentplatform.infrastructure.capability;

import com.agentplatform.domain.capability.CapabilityMaterialization;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.apache.commons.compress.archivers.zip.ZipFile;

/** Extracts a verified version into a private staging directory; this does not authorize a mount. */
public final class SkillMaterialExtractor {
    private static final Set<PosixFilePermission> PRIVATE = Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private final SkillMaterializationVerifier verifier;

    public SkillMaterialExtractor(SkillMaterializationVerifier verifier) { this.verifier = java.util.Objects.requireNonNull(verifier); }

    /** Parent must be an operator-owned private directory inaccessible to the sandbox or other users. */
    public Path extract(Path archive, CapabilityMaterialization.Version version, Path parent, Runnable authorize) throws IOException {
        java.util.Objects.requireNonNull(authorize);
        Path root = parent.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !root.toRealPath().equals(root))
            throw new IllegalArgumentException("MATERIAL_PARENT_INVALID");
        Set<PosixFilePermission> parentPermissions = Files.getPosixFilePermissions(root, LinkOption.NOFOLLOW_LINKS);
        if (parentPermissions.stream().anyMatch(permission -> permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")))
            throw new IllegalArgumentException("MATERIAL_PARENT_NOT_PRIVATE");
        authorize.run();
        verifier.verify(archive, version);
        Path target = Files.createTempDirectory(root, "skill-material-",
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PRIVATE));
        boolean done = false;
        try {
            Map<String, CapabilityMaterialization.File> expected = new HashMap<>();
            for (var file : version.files()) expected.put(file.path(), file);
            Set<String> seen = new HashSet<>();
            try (var channel = java.nio.channels.FileChannel.open(archive, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 var zip = ZipFile.builder().setSeekableByteChannel(channel).get()) {
                var entries = zip.getEntries();
                String wrapper = null;
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = Normalizer.normalize(entry.getName(), Normalizer.Form.NFC);
                    if (name.equals("SKILL.md")) { wrapper = ""; break; }
                    if (name.endsWith("/SKILL.md") && name.indexOf('/') == name.lastIndexOf('/'))
                        wrapper = name.substring(0, name.indexOf('/') + 1);
                }
                if (wrapper == null) throw new IOException("MATERIAL_MANIFEST_MISMATCH");
                entries = zip.getEntries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String full = Normalizer.normalize(entry.getName(), Normalizer.Form.NFC);
                    if (!full.startsWith(wrapper)) throw new IOException("MATERIAL_MANIFEST_MISMATCH");
                    String name = full.substring(wrapper.length());
                    var file = expected.get(name);
                    if (file == null || !seen.add(name)) throw new IOException("MATERIAL_MANIFEST_MISMATCH");
                    Path output = target.resolve(name).normalize();
                    if (!output.startsWith(target)) throw new IOException("MATERIAL_MANIFEST_MISMATCH");
                    Files.createDirectories(output.getParent());
                    MessageDigest digest;
                    try { digest = MessageDigest.getInstance("SHA-256"); }
                    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
                    long size = 0, checkedAt = 0;
                    try (var input = zip.getInputStream(entry); var out = Files.newOutputStream(output,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] buffer = new byte[8192];
                        for (int n; (n = input.read(buffer)) != -1;) {
                            size += n;
                            if (size > file.bytes()) throw new IOException("MATERIAL_FILE_MISMATCH");
                            digest.update(buffer, 0, n); out.write(buffer, 0, n);
                            if (size - checkedAt >= 1024 * 1024) { authorize.run(); checkedAt = size; }
                        }
                    }
                    if (size != file.bytes() || !HexFormat.of().formatHex(digest.digest()).equals(file.sha256()))
                        throw new IOException("MATERIAL_FILE_MISMATCH");
                    authorize.run();
                }
            }
            if (seen.size() != expected.size()) throw new IOException("MATERIAL_MANIFEST_MISMATCH");
            verifier.verify(archive, version);
            authorize.run(); done = true; return target;
        } finally {
            if (!done) removePrivate(target);
        }
    }

    private static void removePrivate(Path target) throws IOException {
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
