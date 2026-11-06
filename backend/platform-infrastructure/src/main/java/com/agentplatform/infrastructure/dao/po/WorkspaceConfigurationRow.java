package com.agentplatform.infrastructure.dao.po;

import java.util.UUID;

public record WorkspaceConfigurationRow(long version, UUID defaultModelId, String defaultCredentialSource,
                                        Boolean defaultPlanEnabled, String defaultApprovalMode) {}
