package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.Actor;
import com.agentplatform.infrastructure.chat.CredentialCipher;
import com.agentplatform.types.TaskPermissionData.Mode;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Schema/ownership compatibility only; this does not claim a runner or production tool effect. */
class WorkspaceToolsMigrationPostgresTest {
    static String url,password;
    com.zaxxer.hikari.HikariDataSource pool;
    ChatRepository repository;Actor actor;UUID workspace,task;
    @BeforeAll static void migrate() throws Exception {
        url=System.getenv("R44_TEST_JDBC_URL");Assumptions.assumeTrue(url!=null,"R44 isolated database not configured; NOT_RUN");
        assertEquals("jdbc:postgresql://127.0.0.1:15457/r44_test?connectTimeout=2&socketTimeout=5",url);
        password=Files.readString(Path.of(System.getenv("R44_TEST_PASSWORD_FILE"))).strip();
        var current=Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").cleanDisabled(false).load();current.clean();
        var previous=Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").target("17").load();previous.migrate();assertEquals("17",previous.info().current().getVersion().toString());
        current.migrate();current.validate();assertEquals("18",current.info().current().getVersion().toString());
    }
    @BeforeEach void fixture() throws Exception {
        var config=new com.zaxxer.hikari.HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword(password);config.setMaximumPoolSize(4);config.setMinimumIdle(1);
        pool=new com.zaxxer.hikari.HikariDataSource(config);repository=ChatPersistenceFixture.create(pool);
        var identity=com.agentplatform.app.identity.IdentityPersistenceFixture.create(pool);String name="r44-"+UUID.randomUUID();
        UUID user=identity.provision(UUID.randomUUID(),name,name,"unused-fixture-hash",false);actor=new Actor(user,identity.spaces(user).getFirst().id());
        UUID model=UUID.fromString("0199e000-0000-7000-8000-000000000002");
        UUID key=repository.importCredential(new CredentialCipher(new byte[32]).encrypt(UUID.randomUUID(),"PLATFORM",null,null,"R44_FIXTURE_ONLY"));repository.bind(model,actor,key,"PLATFORM");
        workspace=UUID.randomUUID();UUID operation=UUID.randomUUID();
        sql("BEGIN;INSERT INTO pf.workspace(id,space_id,actor_id,name,state,quota_bytes,quota_inodes,project_id,operation_id) VALUES('"+workspace+"','"+actor.spaceId()+"','"+actor.userId()+"','R44 metadata','READY',2147483648,100000,(SELECT coalesce(max(project_id),200000)+1 FROM pf.workspace),'"+operation+"');INSERT INTO pf.workspace_operation(id,space_id,workspace_id,actor_id,command_id,status) VALUES('"+operation+"','"+actor.spaceId()+"','"+workspace+"','"+actor.userId()+"','"+UUID.randomUUID()+"','COMMITTED');COMMIT;");
        task=repository.firstAuthorized(actor,UUID.randomUUID(),"R44",model,"PLATFORM","text",false,workspace,Mode.FULL).task().id();
    }
    @AfterEach void close(){if(pool!=null)pool.close();}
    void sql(String value) throws Exception {try(var connection=pool.getConnection();var statement=connection.createStatement()){statement.execute(value);}}
    String scalar(String value) throws Exception {try(var connection=pool.getConnection();var statement=connection.createStatement();var result=statement.executeQuery(value)){assertTrue(result.next());return result.getString(1);}}
    @Test void catalogIsExplicitImmutableAndCannotBindAnotherSpace() throws Exception {
        assertEquals("0",scalar("SELECT count(*) FROM pf.task_tool_catalog WHERE task_id='"+task+"'"));
        sql("INSERT INTO pf.task_tool_catalog(task_id,space_id,workspace_id,actor_id,catalog_version,runtime_bundle_id) SELECT id,space_id,'"+workspace+"',creator_id,'"+WorkspaceTools.VERSION+"',bundle_id FROM pf.task WHERE id='"+task+"'");
        assertThrows(SQLException.class,()->sql("UPDATE pf.task_tool_catalog SET catalog_version='untrusted' WHERE task_id='"+task+"'"));
        assertThrows(SQLException.class,()->sql("UPDATE pf.task_tool_catalog SET workspace_id='"+UUID.randomUUID()+"' WHERE task_id='"+task+"'"));
    }
    @Test void contentRevisionTracksFileFactsButNotLifecycleVersionUpdates() throws Exception {
        String query="SELECT content_revision FROM pf.workspace WHERE id='"+workspace+"'";assertEquals("0",scalar(query));
        sql("UPDATE pf.workspace SET version=version+1 WHERE id='"+workspace+"'");assertEquals("0",scalar(query));
        UUID directory=UUID.randomUUID();sql("INSERT INTO pf.file_entry(id,space_id,workspace_id,relative_path,parent_path,name,kind,collection_state) VALUES('"+directory+"','"+actor.spaceId()+"','"+workspace+"','docs','','docs','DIRECTORY','CONFIRMED')");assertEquals("1",scalar(query));
        sql("UPDATE pf.file_entry SET revision=revision+1 WHERE id='"+directory+"'");assertEquals("1",scalar(query));
        sql("UPDATE pf.file_entry SET collection_state='MISSING' WHERE id='"+directory+"'");assertEquals("2",scalar(query));
    }
    @Test void legacyTaskHasNoRetroactiveProductionCatalogAndKnownFailureIsAStoredFact() throws Exception {
        assertEquals("0",scalar("SELECT count(*) FROM pf.task_tool_catalog WHERE task_id='"+task+"'"));
        assertTrue(scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='pf.tool_operation'::regclass AND conname='tool_operation_status_check'").contains("FAILED"));
        assertEquals("18",scalar("SELECT max(version::int) FROM pf.flyway_schema_history WHERE success"));
    }
    @Test void enabledTaskFreezesANewCatalogAndBundleInTheSubmissionTransaction() throws Exception {
        var enabled=ChatPersistenceFixture.create(pool,true);
        var created=enabled.firstAuthorized(actor,UUID.randomUUID(),"R44 workspace bundle",
                UUID.fromString("0199e000-0000-7000-8000-000000000002"),"PLATFORM","text",true,workspace,Mode.RISK_BASED).task();
        assertEquals("workspace-business-tools-v1",scalar("SELECT catalog_version FROM pf.task_tool_catalog WHERE task_id='"+created.id()+"'"));
        assertEquals("0199e000-0000-7000-8000-000000000020",scalar("SELECT bundle_id FROM pf.task WHERE id='"+created.id()+"'"));
        assertEquals("0199e000-0000-7000-8000-000000000019",scalar("SELECT bundle_id FROM pf.task WHERE id='"+task+"'"));
        assertEquals("0",scalar("SELECT count(*) FROM pf.task_tool_catalog WHERE task_id='"+task+"'"));
    }
}
