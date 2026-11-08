package com.agentplatform.infrastructure.dao.po;

import java.util.UUID;

public record CapabilityBindingRequestRow(String requestHash, UUID setId, long revision, boolean inheritPersonal) {}
