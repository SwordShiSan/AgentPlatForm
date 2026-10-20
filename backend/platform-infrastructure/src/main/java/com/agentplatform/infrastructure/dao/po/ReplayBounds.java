package com.agentplatform.infrastructure.dao.po;

/** Persisted replay floor and committed canonical high-water mark. */
public record ReplayBounds(long current, long prunedThrough) {}
