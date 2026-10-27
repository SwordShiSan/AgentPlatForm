import com.agentplatform.domain.workspace.SandboxRuntime;
import com.agentplatform.infrastructure.workspace.DockerSandboxRuntime;
import com.agentplatform.infrastructure.workspace.SandboxPolicy;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

/** Fixed R4.4 native experiment. No public endpoint, user command or supplier call. */
public final class WorkspaceShellRuntimeProbe {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String COMMAND="""
        set -eu
        python3 -c 'from pathlib import Path;Path("input.txt").write_text("中文😀",encoding="utf-8");print(666)'
        node -e 'const fs=require("fs");if(fs.readFileSync("input.txt","utf8")!=="中文😀")process.exit(9);fs.writeFileSync("result.txt","666");console.log(666)'
        /bin/sh -c 'printf ready > background.ready; sleep 120; printf late > forbidden-late.txt' &
        while [ ! -s background.ready ]; do sleep 0.01; done
        printf '666\n'
        """;
    private static void persist(String name,Object value) throws Exception {
        Path path=Path.of("/reports",name);byte[] bytes=(JSON.writeValueAsString(value)+"\n").getBytes(StandardCharsets.UTF_8);
        try(var channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
    }
    private static void stopped(SandboxRuntime.Output output) {
        if(output.stopReceipt()==null||!output.stopReceipt().execStopped()||!output.stopReceipt().callbacksStopped()
            ||output.stopReceipt().finishedAt()==null||output.timedOut()||output.cancelled())throw new AssertionError("Whole-container stop unconfirmed");
    }
    public static void main(String[] args) throws Exception {
        var input=JSON.readTree(Files.readString(Path.of("/run/fixture/policy.json")));
        String root=input.path("workspaceRoot").asText();
        if(!root.matches("/srv/agentplatform/acceptance/r44-[a-z0-9-]+/workspaces"))throw new IllegalArgumentException("Isolated R44 root required");
        var policy=new SandboxPolicy(input.path("image").asText(),input.path("helperImage").asText(),root,
            UUID.fromString(input.path("mountUuid").asText()),input.path("quotaDevice").asText(),Path.of("/run/fixture/runtime.json"));
        if(args.length==1&&"--policy-only".equals(args[0])){System.out.println(JSON.writeValueAsString(Map.of("policyHash",policy.hash())));return;}
        if(args.length!=0)throw new IllegalArgumentException("Unknown fixed experiment");
        UUID environment=UUID.fromString(input.path("environmentId").asText()),space=UUID.fromString(input.path("spaceId").asText()),workspace=UUID.fromString(input.path("workspaceId").asText());
        var authority=new SandboxRuntime.UseAuthority(){
            public void check(){if(!Files.isRegularFile(Path.of("/run/fixture/allow")))throw new IllegalStateException("Fixture revoked");}
            public void recordExec(String container,String exec){try{persist("exec-"+exec+".json",Map.of("container",container,"exec",exec));}catch(Exception failure){throw new IllegalStateException("Exec receipt unavailable",failure);}}
        };
        var current=new SandboxRuntime.Identity(environment,space,workspace,UUID.randomUUID(),1,1,200001);
        boolean removed=false;
        try(var runtime=new DockerSandboxRuntime(policy)){
            try {
                runtime.prepare(current,authority);long started=System.nanoTime();
                var result=runtime.shell(current,UUID.randomUUID(),new SandboxRuntime.ShellRequest(COMMAND,"",20),authority,()->false);
                stopped(result);long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                if(result.exitCode()!=0||!result.stdout().strip().endsWith("666")||elapsed>=15000)throw new AssertionError("Background stream prevented prompt completion");
                persist("shell-stop.json",result);
                if(runtime.inspect(current).running())throw new AssertionError("Container still running");
                runtime.removeStopped(current,result.stopReceipt());removed=true;
                current=new SandboxRuntime.Identity(environment,space,workspace,UUID.randomUUID(),2,2,200001);
                runtime.prepare(current,authority);removed=false;
                var read=SandboxRuntimeProbe.file(runtime,current,new SandboxRuntime.FileRequest(SandboxRuntime.FileAction.READ,"result.txt",null),authority);
                if(read.exitCode()!=0||!"NjY2".equals(JSON.readTree(read.stdout()).path("contentBase64").asText()))throw new AssertionError("Rebuilt workspace readback differs");
                persist("rebuild-readback.json",read);
                var listing=SandboxRuntimeProbe.file(runtime,current,new SandboxRuntime.FileRequest(SandboxRuntime.FileAction.LIST,"",null),authority);
                if(listing.exitCode()!=0||listing.stdout().contains("forbidden-late.txt"))throw new AssertionError("Late background effect or unreadable workspace");
                var failed=runtime.shell(current,UUID.randomUUID(),new SandboxRuntime.ShellRequest("printf known-error >&2; exit 7","",10),authority,()->false);
                stopped(failed);if(failed.exitCode()!=7||!failed.stderr().contains("known-error"))throw new AssertionError("Known nonzero exit lost");
                persist("nonzero-stop.json",failed);runtime.removeStopped(current,failed.stopReceipt());removed=true;
                persist("result.json",Map.of("status","PASS","policyHash",policy.hash(),"supplierCalls",0,"backgroundCommandMillis",elapsed,
                    "assertions",List.of("actual-gvisor-general-shell-python-node","parent-exit-stops-descendant-and-stream","exact-stop-before-removal","new-generation-file-readback","known-nonzero-exit"),
                    "scope","Native feasibility only; persistent DB/MQ/signed protocol/output-version sealing NOT_RUN"));
                System.out.println("PASS R44 native shell/descendant-stop/rebuild feasibility");
            }finally{
                if(!removed){try{var stop=runtime.stop(current);persist("failure-stop.json",stop);runtime.removeStopped(current,stop);}catch(Exception retained){System.err.println("Exact resource retained for host observation");}}
            }
        }
    }
}
