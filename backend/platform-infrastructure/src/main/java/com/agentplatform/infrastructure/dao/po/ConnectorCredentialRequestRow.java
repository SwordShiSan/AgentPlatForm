package com.agentplatform.infrastructure.dao.po;

import java.time.Instant;
import java.util.UUID;

public record ConnectorCredentialRequestRow(UUID id, long revision, String state, Instant createdAt, String fingerprint) {}
