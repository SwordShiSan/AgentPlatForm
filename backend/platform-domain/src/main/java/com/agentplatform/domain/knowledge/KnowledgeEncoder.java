package com.agentplatform.domain.knowledge;

/** Pinned local embedding model. No network or provider endpoint is accepted. */
public interface KnowledgeEncoder extends AutoCloseable {
    int tokenCount(String text);
    float[] embed(String text, boolean query);
    String modelSha256();
    String tokenizerSha256();
    @Override void close();
}
