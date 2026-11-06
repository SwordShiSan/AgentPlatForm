package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.ConfigurationResolver.CredentialSource;
import com.agentplatform.domain.chat.WorkspaceConfigurationRepository;
import com.agentplatform.infrastructure.dao.chat.WorkspaceConfigurationMapper;
import com.agentplatform.types.TaskPermissionData.Mode;
import com.agentplatform.types.Uuid7;
import java.util.UUID;

/** Uses the Spring-managed MyBatis session; transaction ownership belongs to the application service. */
public final class MyBatisWorkspaceConfigurationRepository implements WorkspaceConfigurationRepository {
    private final WorkspaceConfigurationMapper mapper;
    public MyBatisWorkspaceConfigurationRepository(WorkspaceConfigurationMapper mapper) { this.mapper = mapper; }
    @Override public boolean authorized(Actor actor, UUID workspaceId) { return mapper.authorized(actor, workspaceId); }
    @Override public void lockConfiguration(Actor actor, UUID workspaceId) { mapper.lockUser(actor); mapper.lockWorkspace(actor,workspaceId); }
    @Override public boolean modelEnabled(UUID modelId) { return mapper.modelEnabled(modelId); }
    @Override public View find(Actor actor, UUID workspaceId) {
        var row = mapper.find(actor, workspaceId);
        if (row == null) return new View(0, new Overrides(null, null, null, null));
        return new View(row.version(), new Overrides(row.defaultModelId(),
                row.defaultCredentialSource() == null ? null : CredentialSource.valueOf(row.defaultCredentialSource()),
                row.defaultPlanEnabled(), row.defaultApprovalMode() == null ? null : Mode.valueOf(row.defaultApprovalMode())));
    }
    @Override public void initialize(Actor actor, UUID workspaceId) { mapper.initialize(actor, workspaceId); }
    @Override public boolean update(Actor actor, UUID workspaceId, long expected, Patch patch) { return mapper.update(actor, workspaceId, expected, patch) == 1; }
    @Override public void audit(Actor actor, UUID workspaceId, UUID traceId) { mapper.audit(Uuid7.next(), actor, workspaceId, traceId); }
}
