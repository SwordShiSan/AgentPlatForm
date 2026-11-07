package com.agentplatform.infrastructure.dao.po;

import java.util.UUID;

public record CapabilityBindingSetRow(UUID id, long revision, boolean inheritPersonal) {}
