package com.agentplatform.app.files;

import com.agentplatform.api.files.FileApi;
import com.agentplatform.api.identity.IdentityApi;
import com.agentplatform.domain.identity.IdentityFailure;
import com.agentplatform.trigger.http.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=FileHttpTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.profiles.active=identity-local,workspace-api,file-http-test","server.shutdown=immediate",
                "platform.workspace.enabled=true","platform.workspace.files.enabled=true"})
class FileHttpTest {
    @Configuration(proxyBeanMethods=false) @Profile("file-http-test") @EnableAutoConfiguration
    @Import({FileController.class,HttpSecurityConfiguration.class,ProblemHandler.class,RequestTraceFilter.class})
    static class Config {
        @Bean IdentityApi identity() {return mock(IdentityApi.class);}
        @Bean FileApi files() {return mock(FileApi.class);}
    }
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired IdentityApi identity;
    @org.springframework.beans.factory.annotation.Autowired FileApi files;
    static final String USER="0199e000-0000-7000-8000-000000000001",SPACE="0199e000-0000-7000-8000-000000000002",WORKSPACE="0199e000-0000-7000-8000-000000000003";
    @BeforeEach void setup() {
        reset(identity,files);
        when(identity.me(eq("fixture-session"),isNull())).thenReturn(new IdentityApi.MeResponse(USER,"fixture",false,List.of(new IdentityApi.SpaceResponse(SPACE,"PERSONAL","OWNER",0))));
        when(identity.me(isNull(),any())).thenThrow(new IdentityFailure(401,"AUTHENTICATION_REQUIRED"));
        doAnswer(call->{if(!"fixture-session".equals(call.getArgument(0))||!"fixture-csrf".equals(call.getArgument(1))||!"http://localhost".equals(call.getArgument(2)))throw new IdentityFailure(403,"CSRF_OR_ORIGIN_REJECTED");return null;})
                .when(identity).verifyWrite(any(),any(),any());
    }
    private HttpResponse<String> request(String method,String path,String body,boolean session,boolean csrf) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if(session)builder.header("Cookie","ap_session=fixture-session");
        if(csrf)builder.header("Origin","http://localhost").header("X-CSRF-Token","fixture-csrf");
        builder.header("Idempotency-Key",UUID.randomUUID().toString());
        if(body!=null)builder.header("Content-Type","application/json");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        try(var client=HttpClient.newHttpClient()){return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());}
    }
    @Test void allNewRoutesRequireSessionAndWritesRequireCsrf() throws Exception {
        for(String path:List.of("/files/"+WORKSPACE,"/uploads/"+WORKSPACE,"/file-operations/"+WORKSPACE,"/workspaces/"+WORKSPACE+"/files"))
            assertEquals(401,request("GET","/api/v1"+path,null,false,false).statusCode());
        assertEquals(403,request("POST","/api/v1/workspaces/"+WORKSPACE+"/uploads","{}",true,false).statusCode());
        verifyNoInteractions(files);
    }
    @Test void missingSizeAndUnknownFieldsAreRejectedBeforeService() throws Exception {
        String path="/api/v1/workspaces/"+WORKSPACE+"/uploads";
        String hash="a".repeat(64);
        assertEquals(400,request("POST",path,"{\"path\":\"x\",\"sha256\":\""+hash+"\"}",true,true).statusCode());
        var response=request("POST",path,"{\"path\":\"x\",\"bytes\":0,\"sha256\":\""+hash+"\",\"endpoint\":\"http://foreign\"}",true,true);
        assertEquals(400,response.statusCode());assertTrue(response.body().contains("UNKNOWN_REQUEST_FIELD"));verifyNoInteractions(files);
    }
    @Test void aRevokedSessionCannotContinueAStartedUpload()throws Exception {
        var me=new IdentityApi.MeResponse(USER,"fixture",false,List.of(new IdentityApi.SpaceResponse(SPACE,"PERSONAL","OWNER",0)));
        doReturn(me,me).doThrow(new IdentityFailure(401,"AUTHENTICATION_REQUIRED")).when(identity).me(eq("fixture-session"),isNull());
        var bytes=new java.util.concurrent.atomic.AtomicLong();
        when(files.transfer(any(),any(),anyLong(),any())).thenAnswer(call->{
            var stream=(java.io.InputStream)call.getArgument(3);byte[] buffer=new byte[65536];int count;
            while((count=stream.read(buffer))!=-1)bytes.addAndGet(count);return null;
        });
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/uploads/"+WORKSPACE+"/content")).timeout(Duration.ofSeconds(10))
                .header("Cookie","ap_session=fixture-session").header("Origin","http://localhost").header("X-CSRF-Token","fixture-csrf")
                .header("Content-Type","application/octet-stream").PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[2*1024*1024])).build();
        try(var client=HttpClient.newHttpClient()) {
            assertEquals(401,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertTrue(bytes.get()<=1024*1024);verify(files).transfer(any(),any(),eq(2L*1024*1024),any());
    }
}
