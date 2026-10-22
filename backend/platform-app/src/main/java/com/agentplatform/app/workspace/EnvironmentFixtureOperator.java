package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.EnvironmentUseData.*;
import com.agentplatform.domain.workspace.WorkspaceData.Actor;
import com.agentplatform.infrastructure.dao.workspace.EnvironmentFixtureMapper;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Fixed protected canaries only. No user-supplied paths, scripts, endpoints or Docker parameters. */
@Component @Profile("workspace-environment-fixture")
@ConditionalOnProperty(name="platform.workspace.environment.fixture-enabled",havingValue="true")
public final class EnvironmentFixtureOperator implements ApplicationRunner {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public record Scope(UUID fixtureId,UUID runId,UUID actorId,UUID spaceId,UUID workspaceId,String policyHash) {}
    private final EnvironmentUseRepository repository;
    private final EnvironmentUseApplicationService application;
    private final EnvironmentFixtureMapper mapper;
    private final EnvironmentProperties properties;
    private final ThreadPoolTaskScheduler timers;
    public EnvironmentFixtureOperator(EnvironmentUseRepository repository,EnvironmentUseApplicationService application,EnvironmentFixtureMapper mapper,
            EnvironmentProperties properties,ThreadPoolTaskScheduler timers) {
        this.repository=repository;this.application=application;this.mapper=mapper;this.properties=properties;this.timers=timers;
    }
    private static String option(ApplicationArguments arguments,String name) {
        var values=arguments.getOptionValues(name);if(values==null||values.size()!=1)throw new WorkspaceFailure(400,"MISSING_FIXTURE_OPTION");return values.getFirst();
    }
    static Scope readScope(Path file) {
        try {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>4096)throw new IllegalArgumentException();
            var forbidden=Set.of(PosixFilePermission.GROUP_READ,PosixFilePermission.GROUP_WRITE,PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,PosixFilePermission.OTHERS_WRITE,PosixFilePermission.OTHERS_EXECUTE);
            if(Files.getPosixFilePermissions(file,LinkOption.NOFOLLOW_LINKS).stream().anyMatch(forbidden::contains))throw new IllegalArgumentException();
            var scope=JSON.readValue(Files.readAllBytes(file),Scope.class);
            if(scope.fixtureId()==null||scope.runId()==null||scope.actorId()==null||scope.spaceId()==null||scope.workspaceId()==null||scope.policyHash()==null)throw new IllegalArgumentException();
            return scope;
        } catch(Exception rejected) {throw new WorkspaceFailure(400,"FIXTURE_SCOPE_NOT_PRIVATE");}
    }
    private UUID request(Scope scope,String name) {return UUID.nameUUIDFromBytes((scope.runId()+"/"+name).getBytes(StandardCharsets.UTF_8));}
    private Fact execute(Scope scope,String name,Request body,List<Map<String,Object>> reports) {
        var fact=application.execute(new Actor(scope.actorId(),scope.spaceId()),scope.workspaceId(),request(scope,name),body);
        record(fact,reports);return fact;
    }
    private static void record(Fact fact,List<Map<String,Object>> reports) {
        var item=new LinkedHashMap<String,Object>();item.put("useId",fact.useId());item.put("generation",fact.generation());item.put("fence",fact.fence());
        item.put("resultState",fact.resultState());item.put("factHash",RunnerSignatures.sha256(JSON.writeValueAsBytes(fact)));
        if(fact.output()!=null) {
            item.put("exitCode",fact.output().exitCode());item.put("truncated",fact.output().truncated());
            item.put("stdoutHash",RunnerSignatures.sha256(fact.output().stdout().getBytes(StandardCharsets.UTF_8)));
        }
        item.put("stopConfirmed",fact.stop()!=null);reports.add(item);
    }
    private static void success(Fact fact) {
        if(!"KNOWN".equals(fact.resultState())||fact.output()==null||fact.output().exitCode()!=0)throw new WorkspaceFailure(409,"FIXTURE_ASSERTION_FAILED");
    }
    private void read(Scope scope,String file,String expected,List<Map<String,Object>> reports) {
        var fact=execute(scope,"read/"+file,new Request(new SandboxRuntime.FileRequest(SandboxRuntime.FileAction.READ,file,null),null),reports);success(fact);
        var result=JSON.readTree(fact.output().stdout());byte[] bytes=expected.getBytes(StandardCharsets.UTF_8);
        if(!result.path("ok").asBoolean()||!RunnerSignatures.sha256(bytes).equals(result.path("sha256").asText())
                ||!Arrays.equals(bytes,Base64.getDecoder().decode(result.path("contentBase64").asText())))throw new WorkspaceFailure(409,"FIXTURE_ASSERTION_FAILED");
    }
    @Override public void run(ApplicationArguments arguments) {
        String action=option(arguments,"workspace-environment-action");
        if(!Set.of("roundtrip","readback","output-bound","cancel","timeout").contains(action))throw new WorkspaceFailure(400,"INVALID_FIXTURE_ACTION");
        Scope scope=readScope(Path.of(option(arguments,"fixture-scope-file")));var actual=mapper.scope();
        if(actual==null||!"environment_test".equals(actual.databaseName())||actual.migration()!=11||!scope.fixtureId().equals(actual.fixtureId())
                ||!scope.actorId().equals(actual.actorId())||!scope.spaceId().equals(actual.spaceId())||!scope.workspaceId().equals(actual.workspaceId())
                ||!scope.policyHash().equals(actual.policyHash())||!scope.policyHash().equals(properties.policyHash()))throw new WorkspaceFailure(403,"FIXTURE_SCOPE_MISMATCH");
        var reports=new ArrayList<Map<String,Object>>();
        if("roundtrip".equals(action)) {
            for(var probe:List.of(SandboxRuntime.Probe.PYTHON_666,SandboxRuntime.Probe.NODE_CROSS_READ)) {
                var fact=execute(scope,probe.name(),new Request(null,probe),reports);success(fact);
                if(!"666".equals(fact.output().stdout().strip()))throw new WorkspaceFailure(409,"FIXTURE_ASSERTION_FAILED");
            }
            var write=execute(scope,"file-write",new Request(new SandboxRuntime.FileRequest(SandboxRuntime.FileAction.WRITE,"probe-app.txt",
                    Base64.getEncoder().encodeToString("app-中文😀-666".getBytes(StandardCharsets.UTF_8))),null),reports);success(write);
        }
        if("roundtrip".equals(action)||"readback".equals(action)) {
            read(scope,"probe-python.txt","中文😀",reports);read(scope,"probe-node.txt","666",reports);read(scope,"probe-app.txt","app-中文😀-666",reports);
            var listing=execute(scope,"list-root",new Request(new SandboxRuntime.FileRequest(SandboxRuntime.FileAction.LIST,"",null),null),reports);success(listing);
            var names=new HashSet<String>();var content=JSON.readTree(listing.output().stdout());
            if(!content.path("ok").asBoolean())throw new WorkspaceFailure(409,"FIXTURE_ASSERTION_FAILED");
            for(var item:content.path("entries"))if("FILE".equals(item.path("kind").asText()))names.add(item.path("name").asText());
            if(!names.containsAll(Set.of("probe-python.txt","probe-node.txt","probe-app.txt")))throw new WorkspaceFailure(409,"FIXTURE_ASSERTION_FAILED");
        } else if("output-bound".equals(action)) {
            var fact=execute(scope,action,new Request(null,SandboxRuntime.Probe.OUTPUT_BOUND),reports);success(fact);
            if(!fact.output().truncated()||fact.output().stdout().getBytes(StandardCharsets.UTF_8).length+(long)fact.output().stderr().getBytes(StandardCharsets.UTF_8).length>65536)
                throw new WorkspaceFailure(409,"FIXTURE_ASSERTION_FAILED");
        } else if("cancel".equals(action)||"timeout".equals(action)) {
            var actor=new Actor(scope.actorId(),scope.spaceId());
            var admitted=repository.admit(actor,scope.workspaceId(),request(scope,action),UUID.randomUUID(),new Request(null,SandboxRuntime.Probe.WAIT_FOR_CANCEL));
            var cancellation="cancel".equals(action)&&admitted.fresh()?timers.getScheduledExecutor().schedule(()->application.cancel(actor,admitted.command().id()),2,TimeUnit.SECONDS):null;
            Fact fact;
            try {fact=application.executeAdmitted(actor,admitted);}finally {if(cancellation!=null)cancellation.cancel(false);}
            record(fact,reports);
            if(!"UNKNOWN".equals(fact.resultState())||fact.stop()==null||!repository.stopCommitted(fact.useId()))throw new WorkspaceFailure(409,"FIXTURE_ASSERTION_FAILED");
        }
        System.out.println(JSON.writeValueAsString(Map.of("fixtureId",scope.fixtureId(),"runId",scope.runId(),"action",action,"status","PASS","supplierCalls",0,"uses",reports)));
    }
}
