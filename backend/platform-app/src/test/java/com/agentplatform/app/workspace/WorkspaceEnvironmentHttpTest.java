package com.agentplatform.app.workspace;

import com.agentplatform.api.identity.IdentityApi;
import com.agentplatform.api.workspace.*;
import com.agentplatform.domain.identity.IdentityFailure;
import com.agentplatform.trigger.http.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;

/** Real HTTP/security-filter/DTO contract; identity/application ports are deliberately deterministic. */
@SpringBootTest(classes=WorkspaceEnvironmentHttpTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.profiles.active=identity-local,workspace-api,environment-http-test","server.shutdown=immediate",
                "platform.workspace.enabled=true","platform.workspace.environment.enabled=true"})
class WorkspaceEnvironmentHttpTest {
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired IdentityApi identity;
    @org.springframework.beans.factory.annotation.Autowired WorkspaceEnvironmentApi api;
    private static final String USER="0199e000-0000-7000-8000-000000000001",SPACE="0199e000-0000-7000-8000-000000000002",WORKSPACE="0199e000-0000-7000-8000-000000000003";
    private static final String PATH="/api/v1/workspaces/"+WORKSPACE+"/environment";
    private static final WorkspaceApi.Caller CALLER=new WorkspaceApi.Caller(USER,SPACE);
    @Configuration(proxyBeanMethods=false) @Profile("environment-http-test") @EnableAutoConfiguration
    @Import({WorkspaceEnvironmentController.class,HttpSecurityConfiguration.class,ProblemHandler.class,RequestTraceFilter.class})
    static class Config {
        @Bean IdentityApi identity() {return mock(IdentityApi.class);}
        @Bean WorkspaceEnvironmentApi api() {return mock(WorkspaceEnvironmentApi.class);}
    }
    @BeforeEach void configure() {
        reset(identity,api);
        when(identity.me(eq("fixture-session"),isNull())).thenReturn(new IdentityApi.MeResponse(USER,"fixture",true,
                List.of(new IdentityApi.SpaceResponse(SPACE,"PERSONAL","OWNER",0))));
        when(identity.me(isNull(),any())).thenThrow(new IdentityFailure(401,"AUTHENTICATION_REQUIRED"));
        doAnswer(invocation->{
            if(!"fixture-session".equals(invocation.getArgument(0))||!"fixture-csrf".equals(invocation.getArgument(1))
                    ||!"http://localhost".equals(invocation.getArgument(2)))throw new IdentityFailure(403,"CSRF_OR_ORIGIN_REJECTED");
            return null;
        }).when(identity).verifyWrite(any(),any(),any());
        when(api.get(CALLER,WORKSPACE)).thenReturn(new WorkspaceEnvironmentApi.View(WORKSPACE,"NOT_CREATED","NONE",0,0,null,null,List.of("PREPARE")));
        String operation=UUID.randomUUID().toString();
        when(api.prepare(eq(CALLER),eq(WORKSPACE),anyString(),eq(0L),eq(false))).thenReturn(new WorkspaceEnvironmentApi.Submission(operation,
                new WorkspaceEnvironmentApi.View(WORKSPACE,"PREPARING","PREPARING",1,1,operation,null,List.of())));
    }
    private HttpResponse<String> request(String method,String body,Map<String,String> headers) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+PATH)).timeout(Duration.ofSeconds(10));
        headers.forEach(builder::header);
        if(body!=null)builder.header("Content-Type","application/json");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        try(var client=HttpClient.newHttpClient()) {return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());}
    }
    private Map<String,String> writeHeaders() {
        return new HashMap<>(Map.of("Cookie","ap_session=fixture-session","X-CSRF-Token","fixture-csrf","Origin","http://localhost",
                "Idempotency-Key",UUID.randomUUID().toString(),"If-Match","\"0\""));
    }
    @Test void absentReadHasInitialEtagAndServerDerivedIdentity() throws Exception {
        var result=request("GET",null,Map.of("Cookie","ap_session=fixture-session","X-User-Id","spoofed"));
        assertEquals(200,result.statusCode());assertEquals("\"0\"",result.headers().firstValue("ETag").orElseThrow());
        assertTrue(result.body().contains("NOT_CREATED"));assertFalse(result.body().contains("containerId"));verify(api).get(CALLER,WORKSPACE);
    }
    @Test void emptyObjectAndAbsentBodyReturn202WithOperationAndCurrentRevision() throws Exception {
        for(String body:Arrays.asList(null,"{}")) {
            var result=request("POST",body,writeHeaders());assertEquals(202,result.statusCode());
            assertEquals("\"1\"",result.headers().firstValue("ETag").orElseThrow());assertTrue(result.body().contains("operationId"));
            assertEquals("no-store",result.headers().firstValue("Cache-Control").orElseThrow());
        }
    }
    @Test void nullUnknownFieldsAndArrayAreRejectedBeforeApplication() throws Exception {
        for(String body:List.of("null","{\"imageId\":\"mutable\"}","{\"command\":\"shell\"}","[]"))assertEquals(400,request("POST",body,writeHeaders()).statusCode());
        verifyNoInteractions(api);
    }
    @Test void versionAndIdempotencyHeadersAreRequiredAndStrict() throws Exception {
        for(String missing:List.of("If-Match","Idempotency-Key")) {
            var headers=writeHeaders();headers.remove(missing);assertEquals(428,request("POST","{}",headers).statusCode());
        }
        for(String version:List.of("W/\"0\"","*","\"-1\"","\"00\"","0","\"999999999999999999999999\"")) {
            var headers=writeHeaders();headers.put("If-Match",version);assertEquals(400,request("POST","{}",headers).statusCode());
        }
        verifyNoInteractions(api);
    }
    @Test void identityOriginAndCsrfFailuresNeverReachApplication() throws Exception {
        assertEquals(401,request("GET",null,Map.of()).statusCode());
        for(String missing:List.of("Origin","X-CSRF-Token")) {
            var headers=writeHeaders();headers.remove(missing);assertEquals(403,request("POST","{}",headers).statusCode());
        }
        var crossed=writeHeaders();crossed.put("Origin","http://untrusted.invalid");assertEquals(403,request("POST","{}",crossed).statusCode());
        verifyNoInteractions(api);
    }
}
