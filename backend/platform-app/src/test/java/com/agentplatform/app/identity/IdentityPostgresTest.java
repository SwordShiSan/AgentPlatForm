package com.agentplatform.app.identity;

import com.agentplatform.api.identity.IdentityApi;
import com.agentplatform.domain.identity.IdentityFailure;
import com.agentplatform.domain.identity.IdentityRules;
import com.agentplatform.domain.identity.IdentityStore;
import com.agentplatform.infrastructure.identity.ArgonIdentitySecrets;
import com.agentplatform.domain.identity.IdentityStore;
import com.agentplatform.infrastructure.health.DependencyReadinessProbe;
import com.agentplatform.infrastructure.storage.PostgresStateStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class IdentityPostgresTest {
    private static String url;
    private static String password;
    private static ArgonIdentitySecrets secrets;
    private static String encoded;
    private IdentityStore store;
    private MutableClock clock;
    private IdentityService service;
    private static final String PASSWORD = "Local-fixture-password-2026";
    private static final String ORIGIN = "http://127.0.0.1:18086";
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @BeforeAll static void database() throws Exception {
        url = System.getenv("R11_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null, "Real PostgreSQL fixture not configured; not acceptance evidence");
        assertTrue(url.startsWith("jdbc:postgresql://127.0.0.1:15441/identity_test?") || ("1".equals(System.getenv("R21_ACCEPTANCE")) && url.startsWith("jdbc:postgresql://127.0.0.1:15443/identity_test?")) || ("1".equals(System.getenv("R2C_ACCEPTANCE")) && url.startsWith("jdbc:postgresql://127.0.0.1:15444/identity_test?")) || ("1".equals(System.getenv("R22_ACCEPTANCE")) && url.startsWith("jdbc:postgresql://127.0.0.1:15445/identity_test?")), "Refusing a database outside the explicitly named isolated fixture");
        password = Files.readString(Path.of(System.getenv("R11_TEST_PASSWORD_FILE"))).strip();
        // Only this named isolated test DB is reset; prove a fresh V1 installation every run.
        Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").target("1").load().migrate();
        assertTrue(new DependencyReadinessProbe(new PostgresStateStorage(url,"postgres",password), List.of()).ready());
        Flyway.configure().dataSource(url,"postgres",password).defaultSchema("pf").schemas("pf","pf_runtime").load().migrate();
        secrets = new ArgonIdentitySecrets(); encoded = secrets.passwordHash(PASSWORD);
    }
    @BeforeEach void setup() throws Exception {
        sql("TRUNCATE pf.identity_operation,pf.auth_session,pf.membership,pf.space_storage,pf.space,pf.app_user,pf.auth_rate CASCADE");
        store = com.agentplatform.app.identity.IdentityPersistenceFixture.create(url,"postgres",password); clock = new MutableClock();
        service = new IdentityService(store,secrets,clock,Set.of(ORIGIN));
    }
    private static void sql(String statement) throws Exception { try(var c=DriverManager.getConnection(url,"postgres",password);var q=c.createStatement()){q.execute(statement);} }
    private UUID provision(String name, boolean admin) { return store.provision(UUID.randomUUID(),name,name,encoded,admin); }
    private IdentityApi.SessionResult login(String name) { var csrf=service.csrf(null); return service.login(csrf.cookieToken(),csrf.csrfToken(),ORIGIN,"127.0.0.1",new IdentityApi.LoginRequest(name,PASSWORD)); }
    private static void failure(int status, Runnable work) { assertEquals(status,assertThrows(IdentityFailure.class,work::run).status()); }

    @Test void migrationAndOldReadinessRemainCompatible() {
        assertTrue(store.ready());
        assertTrue(new DependencyReadinessProbe(new PostgresStateStorage(url,"postgres",password), List.of()).ready());
    }
    @Test void missingIdentityRelationDeniesReadiness() throws Exception {
        sql("ALTER TABLE pf.auth_rate RENAME TO auth_rate_unavailable");
        try { assertFalse(store.ready()); }
        finally { sql("ALTER TABLE pf.auth_rate_unavailable RENAME TO auth_rate"); }
    }
    @Test void normalizedUsernameAndPasswordBoundaries() {
        assertEquals("alice",IdentityRules.username("ＡＬＩＣＥ"));
        for(String name:List.of("ab","a".repeat(65),"a b","../a")) failure(400,()->IdentityRules.username(name));
        assertEquals("a".repeat(64),IdentityRules.username("a".repeat(64)));
        failure(400,()->IdentityRules.password("a".repeat(11)));
        failure(400,()->IdentityRules.password("a".repeat(129)));
        IdentityRules.password("a".repeat(12)); IdentityRules.password("a".repeat(128));
    }
    @Test void oneAtomicBundleAndBootstrapOnce() {
        UUID id=provision("owner",true); var spaces=store.spaces(id);
        assertEquals(1,spaces.size()); assertEquals("OWNER",spaces.getFirst().role()); assertEquals(10737418240L,spaces.getFirst().objectLimitBytes());
        assertEquals(7,id.version()); assertEquals(7,spaces.getFirst().id().version());
        failure(409,()->provision("second",true));
        assertNull(store.findUser("second"));
    }
    @Test void retryIsIdempotentAndInputConflictRejected() {
        UUID request=UUID.randomUUID(); UUID a=store.provision(request,"same","alice",encoded,false);
        assertEquals(a,store.provision(request,"same","alice",encoded,false));
        failure(409,()->store.provision(request,"different","alice",encoded,false));
        assertEquals(1,store.spaces(a).size());
    }
    @Test void stablePersonalSpaceAndSessionSurviveStoredDisplayIdentityChange() throws Exception {
        UUID user=provision("alice",false);var before=store.spaces(user);var auth=login("alice");
        // Storage invariant only: R1 does not expose a username-change endpoint or file API.
        sql("UPDATE pf.app_user SET username_norm='renamed_alice' WHERE id='"+user+"'");
        assertEquals(user,store.findUser("renamed_alice").id());assertNull(store.findUser("alice"));
        assertEquals(before,store.spaces(user));
        assertEquals(user.toString(),service.me(auth.cookieToken(),null).id());
        assertEquals("renamed_alice",service.me(auth.cookieToken(),null).username());
    }
    @ParameterizedTest @ValueSource(strings={"space","membership","space_storage","identity_operation"})
    void databaseFailureRollsBackEveryInsertedRow(String table) throws Exception {
        sql("CREATE FUNCTION pf.reject_storage() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
        sql("CREATE TRIGGER reject_storage BEFORE INSERT ON pf."+table+" FOR EACH ROW EXECUTE FUNCTION pf.reject_storage()");
        try { failure(503,()->provision("rollback",false)); assertNull(store.findUser("rollback")); }
        finally { sql("DROP TRIGGER reject_storage ON pf."+table+"; DROP FUNCTION pf.reject_storage()"); }
    }
    @Test void concurrentNamesProduceOneBundle() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var calls=pool.invokeAll(List.of(()->{try{provision("alice",false);return true;}catch(IdentityFailure conflict){assertEquals(409,conflict.status());return false;}},()->{try{provision("alice",false);return true;}catch(IdentityFailure conflict){assertEquals(409,conflict.status());return false;}}));
            assertEquals(1,calls.stream().filter(f->{try{return Boolean.TRUE.equals(f.get());}catch(Exception e){throw new RuntimeException(e);}}).count());
            assertEquals(1,store.spaces(store.findUser("alice").id()).size());
        }
    }
    @Test void sessionRotationAndOnlyHashesAtRest() {
        provision("alice",false); var before=service.csrf(null);
        var authenticated=service.login(before.cookieToken(),before.csrfToken(),ORIGIN,"ip",new IdentityApi.LoginRequest("ALICE",PASSWORD));
        assertNotEquals(before.cookieToken(),authenticated.cookieToken()); assertNotEquals(before.csrfToken(),authenticated.csrfToken());
        assertEquals(43,authenticated.cookieToken().length());
        assertNull(store.session(secrets.hash(before.cookieToken()),clock.now));
        assertEquals(64,store.session(secrets.hash(authenticated.cookieToken()),clock.now).tokenHash().length());
        assertEquals("alice",service.me(authenticated.cookieToken(),null).username());
    }
    @Test void wrongOriginAndCsrfNeverAuthenticate() {
        provision("alice",false);var pre=service.csrf(null);
        failure(403,()->service.login(pre.cookieToken(),pre.csrfToken(),"https://foreign.example","ip",new IdentityApi.LoginRequest("alice",PASSWORD)));
        failure(403,()->service.login(pre.cookieToken(),pre.csrfToken(),null,"ip",new IdentityApi.LoginRequest("alice",PASSWORD)));
        failure(403,()->service.login(pre.cookieToken(),null,ORIGIN,"ip",new IdentityApi.LoginRequest("alice",PASSWORD)));
        failure(401,()->service.me(pre.cookieToken(),null));
    }
    @Test void personalListsAndDetailsDoNotGrantAdminBypass() {
        UUID a=provision("admin",true),b=provision("bob",false);var auth=login("admin");
        assertTrue(service.me(auth.cookieToken(),null).platformAdmin());
        assertEquals(1,service.me(auth.cookieToken(),null).spaces().size());
        UUID foreign=store.spaces(b).getFirst().id();
        failure(404,()->store.space(a,foreign)); failure(404,()->service.me(auth.cookieToken(),foreign.toString()));
    }
    @Test void revokeMembershipTakesEffectWithoutCache() throws Exception {
        UUID a=provision("alice",false);var auth=login("alice");UUID space=store.spaces(a).getFirst().id();
        sql("UPDATE pf.membership SET status='REVOKED',version=version+1 WHERE user_id='"+a+"'");
        assertTrue(service.me(auth.cookieToken(),null).spaces().isEmpty()); failure(404,()->service.me(auth.cookieToken(),space.toString()));
    }
    @Test void logoutDisableAndPasswordResetRevokeImmediately() {
        provision("alice",false);var auth=login("alice");service.logout(auth.cookieToken(),auth.csrfToken(),ORIGIN);
        String loggedOutToken=auth.cookieToken();failure(401,()->service.me(loggedOutToken,null));
        String resetToken=login("alice").cookieToken();store.changeUser("alice",encoded,false,clock.now);failure(401,()->service.me(resetToken,null));
        String disabled=login("alice").cookieToken();store.changeUser("alice",null,true,clock.now);failure(401,()->service.me(disabled,null));
    }
    @Test void absoluteAndIdleBoundaries() {
        provision("alice",false);var auth=login("alice");clock.now=clock.now.plusSeconds(86399);assertNotNull(service.me(auth.cookieToken(),null));
        clock.now=clock.now.plusSeconds(86400);failure(401,()->service.me(auth.cookieToken(),null));
        var fresh=login("alice");
        for(int i=0;i<6;i++){clock.now=clock.now.plusSeconds(86400-1);assertNotNull(service.me(fresh.cookieToken(),null));}
        clock.now=store.session(secrets.hash(fresh.cookieToken()),clock.now).createdAt().plusSeconds(604800);
        failure(401,()->service.me(fresh.cookieToken(),null));
    }
    @Test void preAuthExpiresAndRestartRetainsDurableSessions() {
        provision("alice",false);var pre=service.csrf(null);clock.now=clock.now.plusSeconds(900);
        failure(401,()->service.login(pre.cookieToken(),pre.csrfToken(),ORIGIN,"ip",new IdentityApi.LoginRequest("alice",PASSWORD)));
        var auth=login("alice");var restarted=new IdentityService(com.agentplatform.app.identity.IdentityPersistenceFixture.create(url,"postgres",password),secrets,clock,Set.of(ORIGIN));
        assertEquals("alice",restarted.me(auth.cookieToken(),null).username());
    }
    @Test void ipWindowAndAccountLockPersist() {
        for(int i=0;i<20;i++)assertTrue(store.rate("ip:test",20,60,clock.now));
        assertFalse(store.rate("ip:test",20,60,clock.now));clock.now=clock.now.plusSeconds(60);assertTrue(store.rate("ip:test",20,60,clock.now));
        for(int i=0;i<5;i++)store.accountResult("account:test",false,clock.now);
        assertTrue(store.accountLocked("account:test",clock.now));clock.now=clock.now.plusSeconds(900);assertFalse(store.accountLocked("account:test",clock.now));
    }
    @Test void passwordResetRaceCannotCommitStaleLogin() {
        UUID a=provision("alice",false);var pre=service.csrf(null);store.changeUser("alice",encoded+"changed",false,clock.now);
        failure(401,()->store.authenticateSession(secrets.hash(pre.cookieToken()),encoded,new IdentityStore.Session(secrets.hash("replacement"),secrets.hash("csrf"),a,clock.now,clock.now,clock.now.plusSeconds(604800)),clock.now));
    }
    @Test void unavailableDatabaseFailsClosed() {
        var unavailable=com.agentplatform.app.identity.IdentityPersistenceFixture.create("jdbc:postgresql://127.0.0.1:1/no?connectTimeout=1","none","none");
        assertFalse(unavailable.ready());failure(503,()->unavailable.user(UUID.randomUUID()));
    }
    @Test void argonParametersAndWrongPassword() {
        assertTrue(encoded.startsWith("$argon2id$v=19$m=65536,t=3,p=1$"));assertTrue(secrets.matches(PASSWORD,encoded));assertFalse(secrets.matches("wrong",encoded));
    }
    @Test void passwordWorkHasOneSlot() throws Exception {
        var field=ArgonIdentitySecrets.class.getDeclaredField("slot");field.setAccessible(true);
        var slot=(java.util.concurrent.Semaphore)field.get(secrets);
        slot.acquire();
        try { failure(429,()->secrets.passwordHash(PASSWORD));failure(429,()->secrets.matches(PASSWORD,encoded)); }
        finally { slot.release(); }
        assertTrue(secrets.matches(PASSWORD,encoded));
    }
    @Test void publicNonSecureOriginCannotStart() {
        assertThrows(IllegalArgumentException.class,()->new IdentityConfiguration().identityApi(store,secrets,"http://public.example",false,false));
        assertThrows(IllegalArgumentException.class,()->new IdentityConfiguration().identityApi(store,secrets,"http://localhost.evil.example",false,false));
    }
    @Test void actualLoginFailuresLockAndRecover() {
        provision("alice",false);var pre=service.csrf(null);
        for(int i=0;i<5;i++) failure(401,()->service.login(pre.cookieToken(),pre.csrfToken(),ORIGIN,"ip",new IdentityApi.LoginRequest("alice","wrong-password")));
        failure(401,()->service.login(pre.cookieToken(),pre.csrfToken(),ORIGIN,"ip",new IdentityApi.LoginRequest("alice",PASSWORD)));
        clock.now=clock.now.plusSeconds(900);assertEquals("alice",service.me(login("alice").cookieToken(),null).username());
    }
    @Test void approvedHttpDeploymentIsExplicitAndOriginBound() {
        var configuration=new IdentityConfiguration();
        assertDoesNotThrow(()->configuration.identityApi(store,secrets,"http://agentplatform.lailihao.cloud,http://124.222.55.140",false,true));
        assertThrows(IllegalArgumentException.class,()->configuration.identityApi(store,secrets,"http://public.example",false,true));
        assertThrows(IllegalArgumentException.class,()->configuration.identityApi(store,secrets,"http://124.222.55.140",true,true));
    }

}
