package com.agentplatform.domain.files;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.UUID;

/** Pure rules; filesystem/ACL/fence checks still belong to their authoritative adapters. */
public final class FileRules {
    private FileRules() {}
    public static final long MAX_UPLOAD_BYTES = 100L * 1024 * 1024;
    public static final int MAX_PREVIEW_BYTES = 2 * 1024 * 1024;
    public static String path(String input, boolean rootAllowed) {
        if (input == null) throw new FileFailure(400, "INVALID_FILE_PATH");
        for (int index=0;index<input.length();index++) {
            char value=input.charAt(index);
            if(Character.isHighSurrogate(value)) {
                if(index+1>=input.length()||!Character.isLowSurrogate(input.charAt(++index)))throw new FileFailure(400,"INVALID_FILE_PATH");
            } else if(Character.isLowSurrogate(value))throw new FileFailure(400,"INVALID_FILE_PATH");
        }
        String result = Normalizer.normalize(input, Normalizer.Form.NFC);
        if (result.isEmpty() && rootAllowed) return result;
        if (result.isEmpty() || result.getBytes(StandardCharsets.UTF_8).length > 4096 || result.indexOf('\\') >= 0 || result.indexOf('\0') >= 0)
            throw new FileFailure(400, "INVALID_FILE_PATH");
        for (String part : result.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.getBytes(StandardCharsets.UTF_8).length > 255
                    || part.startsWith(".ap-upload-")) throw new FileFailure(400, "INVALID_FILE_PATH");
        return result;
    }
    public static UUID id(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw new IllegalArgumentException();
            return id;
        } catch (RuntimeException invalid) { throw new FileFailure(400, "INVALID_RESOURCE_ID"); }
    }
    public static String sha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new FileFailure(400, "INVALID_CONTENT_HASH");
        return value;
    }
    public record Range(long first, long last, long length) {}
}
