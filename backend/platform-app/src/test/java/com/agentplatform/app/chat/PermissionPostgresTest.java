package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.infrastructure.chat.CredentialCipher;
import com.agentplatform.types.TaskPermissionData.*;
import com.agentplatform.types.harness.HarnessData;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Metadata/control-plane evidence only. No filesystem, sandbox or supplier assertions. */
class PermissionPostgresTest {
    static String url, password;
    static final UUID MODEL=UUID.fromString("0199e000-0000-7000-8000-000000000002");
    static Actor administrator;
    com.zaxxer.hikari.HikariDataSource pool;ChatRepository repository;
    com.agentplatform.domain.identity.IdentityStore identity;Actor user, admin;
    @BeforeAll static void migrate() throws Exception {
        url=System.getenv("R42_TEST_JDBC_URL");Assumptions.assumeTrue(url!=null,"R42 database not configured: NOT_RUN");
        assertTrue("jdbc:postgresql://127.0.0.1:15455/r42_test?connectTimeout=2&socketTimeout=5".equals(url)
                || ("1".equals(System.getenv("R43_ACCEPTANCE")) && "jdbc:postgresql://127.0.0.1:15456/r43_test?connectTimeout=2&socketTimeout=5".equals(url)));
        password=Files.readString(Path.of(System.getenv("R42_TEST_PASSWORD_FILE"))).strip();
        var flyway=Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").cleanDisabled(false).load();flyway.clean();
        var old=Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").target("15").load();old.migrate();assertEquals("15",old.info().current().getVersion().toString());
        flyway.migrate();flyway.validate();assertEquals("17",flyway.info().current().getVersion().toString());
    }
    @BeforeEach void setup() {
        var config=new com.zaxxer.hikari.HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword(password);config.setMaximumPoolSize(4);config.setMinimumIdle(1);
        pool=new com.zaxxer.hikari.HikariDataSource(config);repository=ChatPersistenceFixture.create(pool);identity=com.agentplatform.app.identity.IdentityPersistenceFixture.create(pool);
        user=actor(false);admin=actor(true);
        UUID key=repository.importCredential(new CredentialCipher(new byte[32]).encrypt(UUID.randomUUID(),"PLATFORM",null,null,"R42_FIXTURE_ONLY"));
        repository.bind(MODEL,user,key,"PLATFORM");repository.bind(MODEL,admin,key,"PLATFORM");
    }
    @AfterEach void close(){if(pool!=null)pool.close();}
    Actor actor(boolean isAdmin) {
        if(isAdmin&&administrator!=null)return administrator;
        String name="r42-"+UUID.randomUUID();UUID id=identity.provision(UUID.randomUUID(),name,name,"unused-password-hash",isAdmin);
        var result=new Actor(id,identity.spaces(id).getFirst().id());if(isAdmin)administrator=result;return result;
    }
    void sql(String query) throws Exception {try(var c=DriverManager.getConnection(url,"postgres",password);var s=c.createStatement()){s.execute(query);}}
    String scalar(String query) throws Exception {try(var c=DriverManager.getConnection(url,"postgres",password);var s=c.createStatement();var r=s.executeQuery(query)){assertTrue(r.next());return r.getString(1);}}
    UUID workspace(Actor actor) throws Exception {
        UUID id=UUID.randomUUID(),operation=UUID.randomUUID();
        sql("BEGIN;INSERT INTO pf.workspace(id,space_id,actor_id,name,state,quota_bytes,quota_inodes,project_id,operation_id) VALUES('"+id+"','"+actor.spaceId()+"','"+actor.userId()+"','R42 metadata fixture','READY',2147483648,100000,(SELECT coalesce(max(project_id),200000)+1 FROM pf.workspace),'"+operation+"');INSERT INTO pf.workspace_operation(id,space_id,workspace_id,actor_id,command_id,status) VALUES('"+operation+"','"+actor.spaceId()+"','"+id+"','"+actor.userId()+"','"+UUID.randomUUID()+"','COMMITTED');COMMIT;");
        return id;
    }
    UUID directory(UUID workspace,String path) throws Exception {
        UUID id=UUID.randomUUID();sql("INSERT INTO pf.file_entry(id,space_id,workspace_id,relative_path,parent_path,name,kind,collection_state) VALUES('"+id+"','"+user.spaceId()+"','"+workspace+"','"+path+"','','"+path+"','DIRECTORY','CONFIRMED')");return id;
    }
    Task task(UUID workspace,Mode mode){return repository.firstAuthorized(user,UUID.randomUUID(),"R42",MODEL,"PLATFORM","text",false,workspace,mode).task();}
    @Test void bindingSnapshotAndIdempotencyAreAtomic() throws Exception {
        UUID workspace=workspace(user),key=UUID.randomUUID();
        var first=repository.firstAuthorized(user,key,"R42",MODEL,"PLATFORM","text",false,workspace,Mode.FULL);
        var replay=repository.firstAuthorized(user,key,"R42",MODEL,"PLATFORM","text",false,workspace,Mode.FULL);
        assertEquals(first.task().id(),replay.task().id());
        var view=repository.permissions(user,first.task().id());assertEquals(workspace.toString(),view.workspaceId());assertEquals(Mode.FULL,view.mode());
        assertEquals(workspace.toString(),((Map<?,?>)repository.configuration(user,first.task().id()).get("values")).get("workspaceId"));
        assertEquals("1",scalar("SELECT count(*) FROM pf.task_resource_binding WHERE task_id='"+first.task().id()+"'"));
        assertEquals(409,assertThrows(ChatFailure.class,()->repository.firstAuthorized(user,key,"R42",MODEL,"PLATFORM","text",false,workspace,Mode.RISK_BASED)).status());
        String count=scalar("SELECT count(*) FROM pf.conversation WHERE space_id='"+user.spaceId()+"'");
        assertThrows(ChatFailure.class,()->task(UUID.randomUUID(),Mode.FULL));
        assertEquals(count,scalar("SELECT count(*) FROM pf.conversation WHERE space_id='"+user.spaceId()+"'"));
    }
    @Test void grantRevokeAndLostResponseDoNotDuplicateFacts() throws Exception {
        UUID workspace=workspace(user),dir=directory(workspace,"docs"),task=task(workspace,Mode.RISK_BASED).id(),key=UUID.randomUUID();
        var request=new CreateGrant(dir.toString(),List.of(Capability.READ,Capability.WRITE));
        var grant=repository.grant(user,task,key,1,request);assertEquals(2,grant.version());assertTrue(grant.grants().getFirst().active());
        assertEquals(grant,repository.grant(user,task,key,1,request));assertEquals(1,repository.permissions(user,task).grants().size());
        assertEquals(412,assertThrows(ChatFailure.class,()->repository.permissionMode(user,task,UUID.randomUUID(),1,Mode.FULL)).status());
        var after=repository.revokeGrant(user,task,UUID.fromString(grant.grants().getFirst().id()),UUID.randomUUID(),2);assertFalse(after.grants().getFirst().active());assertEquals(3,after.version());
        assertEquals("2",scalar("SELECT count(*) FROM pf.platform_event WHERE task_id='"+task+"' AND type='task.permissions_changed'"));
    }
    @Test void concurrentPermissionWritersHaveExactlyOneWinner() throws Exception {
        UUID task=task(null,Mode.RISK_BASED).id();var start=new CountDownLatch(1);var winners=new java.util.concurrent.atomic.AtomicInteger();var stale=new java.util.concurrent.atomic.AtomicInteger();
        Runnable action=()->{try{assertTrue(start.await(5,TimeUnit.SECONDS));repository.permissionMode(user,task,UUID.randomUUID(),1,Mode.FULL);winners.incrementAndGet();}catch(ChatFailure failure){assertEquals(412,failure.status());stale.incrementAndGet();}catch(InterruptedException error){throw new RuntimeException(error);}};
        var a=CompletableFuture.runAsync(action);var b=CompletableFuture.runAsync(action);start.countDown();CompletableFuture.allOf(a,b).get(10,TimeUnit.SECONDS);
        assertEquals(1,winners.get());assertEquals(1,stale.get());assertEquals(2,repository.permissions(user,task).version());
    }
    @Test void scopesCannotCrossTaskSpaceOrAdministratorAcl() throws Exception {
        UUID workspace=workspace(user),dir=directory(workspace,"中文😀"),task=task(workspace,Mode.FULL).id();
        assertTrue(repository.permissionScopes(user,task).items().stream().anyMatch(x->x.scopeId().equals(dir.toString())));
        assertEquals(404,assertThrows(ChatFailure.class,()->repository.permissions(admin,task)).status());
        assertEquals(404,assertThrows(ChatFailure.class,()->repository.grant(admin,task,UUID.randomUUID(),1,new CreateGrant(dir.toString(),List.of(Capability.READ)))).status());
        UUID foreign=workspace(admin);assertThrows(ChatFailure.class,()->task(foreign,Mode.FULL));
        var unbound=task(null,Mode.FULL);assertTrue(repository.permissionScopes(user,unbound.id()).items().isEmpty());
        assertThrows(ChatFailure.class,()->repository.grant(user,unbound.id(),UUID.randomUUID(),1,new CreateGrant(dir.toString(),List.of(Capability.READ))));
    }
    @Test void expiryTerminalAndIndexChangeInvalidateGrants() throws Exception {
        UUID workspace=workspace(user),dir=directory(workspace,"docs"),task=task(workspace,Mode.RISK_BASED).id();
        var grant=repository.grant(user,task,UUID.randomUUID(),1,new CreateGrant(dir.toString(),List.of(Capability.READ)));
        sql("UPDATE pf.file_entry SET collection_state='CHANGED' WHERE id='"+dir+"'");assertFalse(repository.permissions(user,task).grants().getFirst().active());
        sql("UPDATE pf.file_entry SET collection_state='CONFIRMED' WHERE id='"+dir+"';UPDATE pf.task_directory_grant SET created_at=now()-interval '25 hours',expires_at=now()-interval '1 hour' WHERE task_id='"+task+"'");
        assertFalse(repository.permissions(user,task).grants().getFirst().active());
        repository.cancel(user,task,UUID.randomUUID());assertFalse(repository.permissions(user,task).mutable());
        assertThrows(ChatFailure.class,()->repository.permissionMode(user,task,UUID.randomUUID(),grant.version(),Mode.FULL));
    }
    @Test void configurationHashNeverChangesWhenLiveModeChanges() throws Exception {
        UUID task=task(null,Mode.RISK_BASED).id();var before=repository.configuration(user,task);repository.permissionMode(user,task,UUID.randomUUID(),1,Mode.FULL);
        assertEquals(before,repository.configuration(user,task));
        assertEquals(Mode.FULL,repository.permissions(user,task).mode());
        assertThrows(Exception.class,()->sql("UPDATE pf.task_resource_binding SET workspace_id=NULL WHERE task_id='"+task+"'"));
    }
    @Test void directoryCandidatesArePagedWithinTheBoundWorkspace() throws Exception {
        UUID workspace=workspace(user),task=task(workspace,Mode.RISK_BASED).id();
        for(int n=0;n<52;n++)directory(workspace,String.format("dir%02d",n));
        var first=repository.permissionScopes(user,task);assertEquals(51,first.items().size());assertNotNull(first.nextCursor());
        var second=repository.permissionScopes(user,task,first.nextCursor());assertEquals(2,second.items().size());assertNull(second.nextCursor());
        assertThrows(ChatFailure.class,()->repository.permissionScopes(user,task,UUID.randomUUID().toString()));
    }
    @Test void deniedCapabilitiesAndStaleFencesNeverStartTools() {
        var task=task(null,Mode.FULL);UUID relay=UUID.randomUUID();var publication=repository.publications(relay).stream().filter(x->x.envelope().aggregateId().equals(task.id())).findFirst().orElseThrow();repository.published(relay,publication,true);
        var lease=repository.claim(publication.envelope(),UUID.randomUUID());assertNotNull(lease);
        var call=new HarnessData.Call("never","workspace_file_read",Map.of("path","docs/a"),true);
        assertEquals(HarnessData.Decision.DENY,repository.operationDecision(lease,call));
        assertThrows(ChatFailure.class,()->repository.startTool(lease,call));
        repository.stopped(lease,"FAILED","FIXTURE_COMPLETE",null,"e".repeat(64));
        assertThrows(ChatFailure.class,()->repository.operationDecision(lease,call));
    }
}
