package com.agentplatform.app.capability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

/** Reads bounded key files and rejects reuse of the model credential key. */
final class IndependentMcpKeyLoader {
    private IndependentMcpKeyLoader() {}
    static byte[] load(String mcpFile, String modelFile) throws IOException {
        Path mcpPath = Path.of(mcpFile).toRealPath(), modelPath = Path.of(modelFile).toRealPath();
        if (Files.isSameFile(mcpPath, modelPath)) throw new IllegalArgumentException("MCP master key must be independent");
        byte[] mcp = read(mcpPath), model = null;
        try {
            model = read(modelPath);
            if (MessageDigest.isEqual(mcp, model)) throw new IllegalArgumentException("MCP master key must be independent");
            return mcp.clone();
        } finally { Arrays.fill(mcp, (byte) 0); if (model != null) Arrays.fill(model, (byte) 0); }
    }
    private static byte[] read(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(33);
            if (bytes.length != 32) { Arrays.fill(bytes, (byte) 0); throw new IllegalArgumentException("Master key must contain 32 bytes"); }
            return bytes;
        }
    }
}
