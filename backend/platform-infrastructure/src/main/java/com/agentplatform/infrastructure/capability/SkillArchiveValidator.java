package com.agentplatform.infrastructure.capability;

import io.agentscope.core.skill.util.MarkdownSkillParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.AsiExtraField;
import org.apache.commons.compress.archivers.zip.ZipFile;

/** Validates a staged archive without extracting it or executing package content. */
public final class SkillArchiveValidator {
    public static final long MAX_ARCHIVE = 20L * 1024 * 1024;
    public static final long MAX_EXPANDED = 100L * 1024 * 1024;
    public static final int MAX_FILES = 1000;
    private static final int MAX_METADATA = 64 * 1024;

    public record FileEntry(String path, long bytes, String sha256) {}
    public record Manifest(String archiveSha256, String name, String description, List<FileEntry> files, long expandedBytes,
                           String dependenciesJson) {
        public Manifest { files = List.copyOf(files); }
    }

    public Manifest validate(Path archive) throws IOException {
        Objects.requireNonNull(archive, "archive");
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS))
            throw invalid("ARCHIVE_SIZE_OR_TYPE");
        try (var channel = FileChannel.open(archive, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
          if (channel.size() > MAX_ARCHIVE) throw invalid("ARCHIVE_SIZE_OR_TYPE");
          String archiveHash = archiveHash(channel);
          try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(channel).get()) {
            var entries = new ArrayList<ZipArchiveEntry>();
            var names = new HashSet<String>();
            var all = zip.getEntries();
            while (all.hasMoreElements()) {
                ZipArchiveEntry e = all.nextElement();
                String name = path(e.getName(), e.isDirectory());
                if (!names.add(name) || !zip.canReadEntryData(e) || e.getGeneralPurposeBit().usesEncryption()
                        || e.isUnixSymlink() || invalidUnixType(e) || containsUnixLink(e)) throw invalid("INVALID_ENTRY");
                entries.add(e);
                if (entries.size() > MAX_FILES * 2) throw invalid("ENTRY_LIMIT");
            }
            String prefix = wrapper(names);
            var regularPaths = new HashSet<String>();
            for (ZipArchiveEntry entry : entries) {
                if (!entry.isDirectory()) regularPaths.add(path(entry.getName(), false));
            }
            for (String name : names) {
                int slash = name.indexOf('/');
                while (slash >= 0) {
                    if (regularPaths.contains(name.substring(0, slash))) throw invalid("FILE_DIRECTORY_CONFLICT");
                    slash = name.indexOf('/', slash + 1);
                }
            }
            var files = new ArrayList<FileEntry>();
            long expanded = 0;
            byte[] skill = null;
            for (ZipArchiveEntry entry : entries) {
                String raw = path(entry.getName(), entry.isDirectory());
                if (entry.isDirectory()) continue;
                String name = raw.substring(prefix.length());
                if (name.isEmpty()) throw invalid("INVALID_ENTRY");
                if (files.size() >= MAX_FILES) throw invalid("FILE_LIMIT");
                MessageDigest digest = sha256();
                CRC32 crc = new CRC32();
                ByteArrayOutputStream metadata = name.equals("SKILL.md") ? new ByteArrayOutputStream() : null;
                long size = 0;
                try (var in = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[8192];
                    for (int n; (n = in.read(buffer)) != -1;) {
                        size += n;
                        expanded += n;
                        if (expanded > MAX_EXPANDED || metadata != null && size > MAX_METADATA) throw invalid("EXPANDED_LIMIT");
                        digest.update(buffer, 0, n);
                        crc.update(buffer, 0, n);
                        if (metadata != null) metadata.write(buffer, 0, n);
                    }
                }
                if (entry.getSize() != size || entry.getCrc() != crc.getValue()) throw invalid("ENTRY_INTEGRITY");
                files.add(new FileEntry(name, size, HexFormat.of().formatHex(digest.digest())));
                if (metadata != null) skill = metadata.toByteArray();
            }
            if (skill == null) throw invalid("MISSING_SKILL_MD");
            String markdown;
            try {
                markdown = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(skill)).toString();
            } catch (java.nio.charset.CharacterCodingException badText) { throw invalid("INVALID_UTF8"); }
            var parsed = MarkdownSkillParser.parse(markdown);
            Map<String, Object> meta = parsed.getMetadata();
            if (!parsed.hasFrontmatter() || !(meta.get("name") instanceof String name) || name.isBlank()
                    || name.getBytes(StandardCharsets.UTF_8).length > 512
                    || !(meta.get("description") instanceof String description) || description.isBlank()
                    || description.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA)
                throw invalid("INVALID_SKILL_METADATA");
            files.sort(java.util.Comparator.comparing(FileEntry::path));
            if (!archiveHash.equals(archiveHash(channel))) throw invalid("ARCHIVE_CHANGED");
            // Preserve declarations; publication must explicitly resolve every required dependency.
            Object dependencies = meta.get("dependencies");
            String dependenciesJson;
            try {
                dependenciesJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                        dependencies == null ? List.of() : dependencies instanceof List<?> list ? list : List.of(dependencies));
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw invalid("INVALID_DEPENDENCIES"); }
            if (dependenciesJson.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA) throw invalid("INVALID_DEPENDENCIES");
            return new Manifest(archiveHash, name, description, files, expanded, dependenciesJson);
          }
        }
    }

    private static boolean invalidUnixType(ZipArchiveEntry entry) {
        int type = entry.getUnixMode() & 0170000;
        return type != 0 && type != (entry.isDirectory() ? 0040000 : 0100000);
    }

    private static boolean containsUnixLink(ZipArchiveEntry entry) {
        for (var field : entry.getExtraFields()) {
            if (field instanceof AsiExtraField unix && (unix.isLink()
                    || unix.getLinkedFile() != null && !unix.getLinkedFile().isEmpty())) return true;
        }
        return false;
    }

    private static String path(String name, boolean directory) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\")
                || name.indexOf('\0') >= 0 || name.matches("^[A-Za-z]:.*")) throw invalid("INVALID_PATH");
        if (directory && name.endsWith("/")) name = name.substring(0, name.length() - 1);
        name = Normalizer.normalize(name, Normalizer.Form.NFC);
        if (name.getBytes(StandardCharsets.UTF_8).length > 4096) throw invalid("INVALID_PATH");
        for (String segment : name.split("/", -1))
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.getBytes(StandardCharsets.UTF_8).length > 255) throw invalid("INVALID_PATH");
        return name;
    }

    private static String wrapper(Set<String> names) {
        if (names.contains("SKILL.md")) return "";
        var roots = names.stream().filter(n -> n.endsWith("/SKILL.md") && n.indexOf('/') == n.lastIndexOf('/')).toList();
        if (roots.size() != 1) throw invalid("MISSING_SKILL_MD");
        String root = roots.get(0).substring(0, roots.get(0).indexOf('/'));
        if (names.stream().anyMatch(n -> !n.equals(root) && !n.startsWith(root + "/"))) throw invalid("MULTIPLE_ROOTS");
        return root + "/";
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String archiveHash(FileChannel channel) throws IOException {
        channel.position(0);
        MessageDigest digest = sha256();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        long total = 0;
        while (channel.read(buffer) != -1) {
            total += buffer.position();
            if (total > MAX_ARCHIVE) throw invalid("ARCHIVE_SIZE_OR_TYPE");
            buffer.flip();
            digest.update(buffer);
            buffer.clear();
        }
        channel.position(0);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
}
