package com.agentplatform.domain.capability;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable approved inputs for a single test task, not permission to mount them. */
public final class CapabilityMaterialization {
    public static final long MAX_BYTES = 256L * 1024 * 1024;
    public static final int MAX_FILES = 4000;
    private static final String FORMAT = "capability-materialization-v1";

    public record File(String path, long bytes, String sha256) {
        public File {
            path = safePath(path);
            if (bytes < 0 || bytes > MAX_BYTES) throw new IllegalArgumentException("INVALID_CAPABILITY_SIZE");
            checkHash(sha256);
        }
    }

    public record Version(UUID capabilityId, UUID versionId, String archiveSha256, List<File> files) {
        public Version {
            Objects.requireNonNull(capabilityId, "capabilityId");
            Objects.requireNonNull(versionId, "versionId");
            checkHash(archiveSha256);
            Objects.requireNonNull(files, "files");
            if (files.isEmpty()) throw new IllegalArgumentException("EMPTY_CAPABILITY");
            if (files.size() > MAX_FILES) throw new IllegalArgumentException("CAPABILITY_MATERIAL_LIMIT");
            var sorted = new ArrayList<>(files);
            sorted.sort(Comparator.comparing(File::path));
            var names = new HashSet<String>();
            for (var file : sorted) if (!names.add(file.path())) throw new IllegalArgumentException("DUPLICATE_CAPABILITY_PATH");
            for (var file : sorted) {
                int slash = file.path().indexOf('/');
                while (slash >= 0) {
                    if (names.contains(file.path().substring(0, slash))) throw new IllegalArgumentException("FILE_DIRECTORY_CONFLICT");
                    slash = file.path().indexOf('/', slash + 1);
                }
            }
            files = List.copyOf(sorted);
        }
        public String mountTarget() { return "/capabilities/" + capabilityId + "/" + versionId; }
    }

    public record Manifest(UUID spaceId, UUID taskId, List<Version> versions) {
        public Manifest {
            Objects.requireNonNull(spaceId, "spaceId");
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(versions, "versions");
            if (versions.size() > CapabilityBindings.MAX_BINDINGS) throw new IllegalArgumentException("CAPABILITY_BINDING_LIMIT");
            var sorted = new ArrayList<>(versions);
            sorted.sort(Comparator.comparing((Version v) -> v.capabilityId().toString()).thenComparing(v -> v.versionId().toString()));
            var ids = new HashSet<UUID>();
            long bytes = 0;
            int count = 0;
            for (Version version : sorted) {
                if (!ids.add(version.capabilityId())) throw new IllegalArgumentException("DUPLICATE_CAPABILITY_BINDING");
                for (File file : version.files()) {
                    bytes = Math.addExact(bytes, file.bytes());
                    if (++count > MAX_FILES || bytes > MAX_BYTES) throw new IllegalArgumentException("CAPABILITY_MATERIAL_LIMIT");
                }
            }
            versions = List.copyOf(sorted);
        }

        /** Versioned, length-prefixed canonical bytes avoid ambiguous field concatenation. */
        public String hash() {
            try {
                var bytes = new ByteArrayOutputStream();
                try (var out = new DataOutputStream(bytes)) {
                    field(out, FORMAT); field(out, spaceId.toString()); field(out, taskId.toString());
                    out.writeInt(versions.size());
                    for (var version : versions) {
                        field(out, version.capabilityId().toString()); field(out, version.versionId().toString());
                        field(out, version.archiveSha256()); field(out, version.mountTarget());
                        field(out, "read-only"); out.writeInt(version.files().size());
                        for (var file : version.files()) { field(out, file.path()); out.writeLong(file.bytes()); field(out, file.sha256()); }
                    }
                }
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    private static void field(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }

    private static String safePath(String value) {
        Objects.requireNonNull(value, "path");
        if (value.isEmpty() || value.startsWith("/") || value.contains("\\") || value.indexOf('\0') >= 0
                || value.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException("INVALID_CAPABILITY_PATH");
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        if (normalized.getBytes(StandardCharsets.UTF_8).length > 4096) throw new IllegalArgumentException("INVALID_CAPABILITY_PATH");
        for (String part : normalized.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.getBytes(StandardCharsets.UTF_8).length > 255)
                throw new IllegalArgumentException("INVALID_CAPABILITY_PATH");
        return normalized;
    }

    private static void checkHash(String hash) {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("INVALID_CAPABILITY_HASH");
    }

    private CapabilityMaterialization() {}
}
