package com.agentplatform.app.chat;

import com.agentplatform.app.workspace.TransactionalEnvironmentUseRepository;
import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.domain.workspace.*;
import com.agentplatform.infrastructure.adapter.repository.workspace.MyBatisEnvironmentUseRepository;
import com.agentplatform.infrastructure.dao.typehandler.UuidTypeHandler;
import com.agentplatform.infrastructure.dao.workspace.*;
import com.agentplatform.types.TaskPermissionData.Mode;
import com.agentplatform.types.harness.HarnessData.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual PG/Mapper child ownership, not a native execution or runner receipt substitute. */
class WorkspaceShellAdmissionPostgresTest extends WorkspaceToolsMigrationPostgresTest {
    private EnvironmentUseRepository uses;private Lease current;private Call tool;
    private WorkspaceData.Actor workspaceActor(){return new WorkspaceData.Actor(actor.userId(),actor.spaceId());}
    private WorkspaceShellData.Request prepared() throws Exception {
        UUID environment=UUID.randomUUID(),operation=UUID.randomUUID(),command=UUID.randomUUID();
        sql("BEGIN;INSERT INTO pf.workspace_environment(id,space_id,workspace_id,generation,fence,state,image_id,policy_hash,creation_command_id,creation_fence,operation_id,container_id,container_started_at,readiness_hash) VALUES('"+environment+"','"+actor.spaceId()+"','"+workspace+"',1,1,'READY','sha256:"+"a".repeat(64)+"','"+"b".repeat(64)+"','"+command+"',1,'"+operation+"','"+"c".repeat(64)+"','2026-10-27T00:00:00Z','"+"d".repeat(64)+"');"
            +"INSERT INTO pf.workspace_environment_operation(id,space_id,workspace_id,environment_id,kind,initiator,actor_id,command_id,generation,fence,status,effect_state,owner_id,lease_until,deadline_at) VALUES('"+operation+"','"+actor.spaceId()+"','"+workspace+"','"+environment+"','PREPARE','USER','"+actor.userId()+"','"+command+"',1,1,'COMMITTED','READY','"+UUID.randomUUID()+"',clock_timestamp()+interval '60 seconds',clock_timestamp()+interval '120 seconds');"
            +"UPDATE pf.workspace_sandbox_slot SET space_id='"+actor.spaceId()+"',workspace_id='"+workspace+"',environment_id='"+environment+"',generation=1,revision=revision+1 WHERE id=1;COMMIT;");
        var factory=new SqlSessionFactoryBean();factory.setDataSource(pool);var configuration=new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);configuration.setCacheEnabled(false);configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);configuration.getTypeHandlerRegistry().register(new UuidTypeHandler());
        factory.setConfiguration(configuration);factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/**/*.xml"));
        var session=new SqlSessionTemplate(factory.getObject());uses=new TransactionalEnvironmentUseRepository(new MyBatisEnvironmentUseRepository(session.getMapper(EnvironmentUseMapper.class),
            session.getMapper(EnvironmentPreparationMapper.class),session.getMapper(EnvironmentExecutionMapper.class),session.getMapper(WorkspaceMapper.class)),new TransactionTemplate(new JdbcTransactionManager(pool)));
        repository=ChatPersistenceFixture.create(pool,true);
        var task=repository.firstAuthorized(actor,UUID.randomUUID(),"R44 shell parent",UUID.fromString("0199e000-0000-7000-8000-000000000002"),"PLATFORM","shell admission",false,workspace,Mode.FULL).task();
        var publication=repository.publications(UUID.randomUUID()).stream().filter(p->p.envelope().aggregateId().equals(task.id())).findFirst().orElseThrow();
        current=repository.claim(publication.envelope(),UUID.randomUUID());assertNotNull(current);
        tool=new Call("shell-owned","workspace_shell",Map.of("command","printf 666","cwd","","timeoutSeconds",20,"outputs",List.of()),false);
        var turn=repository.beginTurn(current);var call=repository.beginCall(current,false);repository.startCall(current,call,false);
        repository.commitRound(current,turn,new Turn(UUID.randomUUID().toString(),1,new Round("",List.of(tool),"tool_calls",10,2)),new ExecutionLedger.CallResult(call,true,"r44-shell-metadata",false));
        repository.prepareWorkspaceTools(current,List.of(tool),"sha256:"+"a".repeat(64),"b".repeat(64));repository.startTool(current,tool);
        return repository.workspaceShellRequest(current,tool);
    }
    @AfterEach void retireMetadataFixtures() throws Exception {
        if(current!=null)sql("UPDATE pf.execution_slot SET task_id=NULL,owner_id=NULL,lease_until=NULL,fencing_token=fencing_token+1 WHERE task_id='"+current.taskId()+"' AND owner_id='"+current.ownerId()+"'");
        sql("UPDATE pf.workspace_sandbox_slot SET space_id=NULL,workspace_id=NULL,environment_id=NULL,generation=NULL,revision=revision+1 WHERE workspace_id='"+workspace+"'");
    }
    @Test void shellUseIsUniquelyLinkedToTheOriginalTaskAndEnvironmentFenceIsIndependent() throws Exception {
        var request=prepared();UUID id=request.parent().operationId();
        var admitted=uses.admitShell(workspaceActor(),workspace,id,current.ownerId(),request);assertTrue(admitted.fresh());assertEquals("SHELL",admitted.command().kind());
        assertEquals(request,admitted.command().request());assertEquals(current.fence(),request.parent().taskFence());assertEquals(2,admitted.command().fence());
        var repeated=uses.admitShell(workspaceActor(),workspace,id,current.ownerId(),request);assertFalse(repeated.fresh());assertEquals(admitted.command(),repeated.command());
        assertEquals("1",scalar("SELECT count(*) FROM pf.workspace_tool_shell_admission WHERE task_id='"+current.taskId()+"'"));
        assertEquals("1",scalar("SELECT count(*) FROM pf.workspace_tool_job_binding WHERE task_id='"+current.taskId()+"'"));
        uses.renew(admitted.command());repository.cancel(actor,current.taskId(),UUID.randomUUID());
        assertThrows(WorkspaceFailure.class,()->uses.checkOwnership(admitted.command()));
        assertEquals("1",scalar("SELECT count(*) FROM pf.workspace_sandbox_slot WHERE workspace_id='"+workspace+"'"));
    }
    @Test void changedCommandCannotReuseAValidParentApprovalAndAdmissionHasNoPartialRows() throws Exception {
        var request=prepared();var altered=new WorkspaceShellData.Request(WorkspaceShellData.PROTOCOL,request.parent(),new SandboxRuntime.ShellRequest("printf different","",20));
        assertEquals("SHELL_PARENT_NOT_AUTHORIZED",assertThrows(WorkspaceFailure.class,()->uses.admitShell(workspaceActor(),workspace,request.parent().operationId(),current.ownerId(),altered)).code());
        assertEquals("0",scalar("SELECT count(*) FROM pf.workspace_environment_use WHERE workspace_id='"+workspace+"'"));
        assertEquals("1",scalar("SELECT fence FROM pf.workspace_environment WHERE workspace_id='"+workspace+"'"));
    }
    @Test void expiredTaskDeadlineCannotBeExtendedByASeparateShellUseLease() throws Exception {
        var request=prepared();sql("UPDATE pf.task SET execution_deadline_at=clock_timestamp()-interval '1 second' WHERE id='"+current.taskId()+"'");
        assertThrows(WorkspaceFailure.class,()->uses.admitShell(workspaceActor(),workspace,request.parent().operationId(),current.ownerId(),request));
        assertEquals("0",scalar("SELECT count(*) FROM pf.workspace_environment_use WHERE workspace_id='"+workspace+"'"));
    }
}
