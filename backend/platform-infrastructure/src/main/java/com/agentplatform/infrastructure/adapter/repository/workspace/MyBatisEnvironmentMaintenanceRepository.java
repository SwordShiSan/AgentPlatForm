package com.agentplatform.infrastructure.adapter.repository.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.EnvironmentExecutionData.*;
import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import com.agentplatform.infrastructure.dao.po.EnvironmentRows.Environment;
import com.agentplatform.infrastructure.dao.workspace.*;
import com.agentplatform.types.Uuid7;
import java.util.LinkedHashMap;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** No network/effect calls. STOP is an outbox operation, not an in-transaction Docker call. */
public final class MyBatisEnvironmentMaintenanceRepository implements EnvironmentMaintenanceRepository {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final EnvironmentMaintenanceMapper mapper;
    private final EnvironmentPreparationMapper environments;
    private final EnvironmentExecutionMapper execution;
    private final WorkspaceMapper workspaces;
    public MyBatisEnvironmentMaintenanceRepository(EnvironmentMaintenanceMapper mapper,EnvironmentPreparationMapper environments,
            EnvironmentExecutionMapper execution,WorkspaceMapper workspaces) {
        this.mapper=mapper;this.environments=environments;this.execution=execution;this.workspaces=workspaces;
    }
    private void tx() {if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Environment maintenance transaction required");}
    private Environment lock(Command command) {
        workspaces.lockWorkspace(command.spaceId(),command.workspaceId());
        var environment=environments.lockEnvironment(command.spaceId(),command.workspaceId());
        environments.operation(command.operationId());return environment;
    }
    private void event(Environment row) {
        var payload=new LinkedHashMap<String,Object>();payload.put("workspaceId",row.workspaceId().toString());payload.put("state",row.state());
        payload.put("generation",row.generation());payload.put("version",row.version());payload.put("operationId",row.operationId().toString());payload.put("errorCode",row.errorCode());
        environments.insertEvent(Uuid7.next(),row,JSON.writeValueAsString(payload));
    }
    @Override public int blockExpired() {
        tx();environments.lockSlot();var id=mapper.expiredOperation();if(id==null)return 0;
        var command=execution.command(id);var row=lock(command);
        if(row==null||!row.operationId().equals(id)||mapper.blockExpired(id)!=1)return 0;
        var projection=new Receipt(command.commandId(),id,command.environmentId(),command.workspaceId(),command.generation(),command.fence(),
                "UNKNOWN","MAY_HAVE_STARTED","ENVIRONMENT_OWNER_EXPIRED",null,null,1);
        if(execution.projectEnvironment(command,projection,"BLOCKED")!=1)throw new WorkspaceFailure(409,"ENVIRONMENT_CONCURRENT_CHANGE");
        execution.projectWorkspace(command,"BLOCKED");event(environments.environment(command.spaceId(),command.workspaceId()));
        // Neither slot nor owner/command is replaced. Expiry is not termination proof.
        return 1;
    }
    @Override public int scheduleStop() {
        tx();var id=mapper.readyOperation();if(id==null)return 0;
        var command=execution.command(id);
        boolean live=workspaces.authorized(new Actor(command.actorId(),command.spaceId()));
        var slot=environments.lockSlot();var row=lock(command);
        if(row==null||!row.operationId().equals(id)||!"READY".equals(row.state()))return 0;
        if(!row.id().equals(slot.environmentId())||!Objects.equals(row.generation(),slot.generation()))throw new WorkspaceFailure(503,"ENVIRONMENT_CAPACITY_INCONSISTENT");
        // An unresolved data-plane owner must converge with its own stop barrier or
        // trusted host proof. A fresh controller cannot assert that owner's callbacks stopped.
        if(environments.unresolvedUse(row.id())||environments.lifecycleBusy(row.spaceId(),row.workspaceId())
                ||workspaces.fileDataPlaneBusy(row.spaceId(),row.workspaceId())||mapper.fileOperationBusy(row.spaceId(),row.workspaceId()))return 0;
        if(live&&!mapper.idle(row.id()))return 0;
        var operation=Uuid7.next();long fence=Math.addExact(row.fence(),1);
        mapper.insertStop(command,operation,Uuid7.next(),fence);
        if(mapper.selectStop(command,operation,fence,row.fence())!=1)throw new WorkspaceFailure(409,"ENVIRONMENT_CONCURRENT_CHANGE");
        row=environments.environment(command.spaceId(),command.workspaceId());
        environments.insertOutbox(Uuid7.next(),row);execution.projectWorkspace(command,row.state());event(row);
        return 1;
    }
}
