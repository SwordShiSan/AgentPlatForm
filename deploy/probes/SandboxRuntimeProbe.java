import com.agentplatform.domain.workspace.SandboxRuntime;
import com.agentplatform.domain.workspace.FileHelperData;
import com.agentplatform.infrastructure.workspace.DockerSandboxRuntime;
import com.agentplatform.infrastructure.workspace.SandboxPolicy;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.json.JsonMapper;

/** Private native feasibility only, not a substitute for persisted DB/MQ acceptance. */
public final class SandboxRuntimeProbe {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static void main(String[] args) throws Exception {
        var input = JSON.readTree(Files.readString(Path.of("/run/fixture/policy.json")));
        String root = input.path("workspaceRoot").asText();
        if (!root.matches("/srv/agentplatform/acceptance/r32-[a-z0-9-]+/workspaces")) throw new IllegalArgumentException("Private fixture only");
        var policy = new SandboxPolicy(input.path("image").asText(), input.path("helperImage").asText(), root,
                UUID.fromString(input.path("mountUuid").asText()), input.path("quotaDevice").asText(), Path.of("/run/fixture/runtime.json"));
        UUID environment = UUID.fromString(input.path("environmentId").asText());
        UUID space = UUID.fromString(input.path("spaceId").asText());
        UUID workspace = UUID.fromString(input.path("workspaceId").asText());
        var identity = new SandboxRuntime.Identity(environment, space, workspace, UUID.randomUUID(), 1, 1, 200001);
        Path report = Path.of("/reports/result.json");
        List<String> assertions = new ArrayList<>();
        var submittedAt = new AtomicLong();
        var authority = new SandboxRuntime.UseAuthority() {
            @Override public void check() {
                if (!Files.exists(Path.of("/run/fixture/allow"))) throw new IllegalStateException("Fixture revoked");
            }
            @Override public void recordExec(String container, String exec) {
                // This file is only the feasibility observer; production must use the fenced DB transaction.
                persist(Path.of("/reports/exec-bindings.jsonl"), JSON.writeValueAsString(Map.of("container", container, "exec", exec)) + "\n");
                submittedAt.set(System.nanoTime());
            }
        };
        try (var runtime = new DockerSandboxRuntime(policy)) {
            SandboxRuntime.Identity current = identity;
            boolean removed = false;
            try {
                var container = runtime.prepare(current, authority);
                if (!container.running()) throw new AssertionError("Not running");
                assertions.add("locked-gvisor-container-and-policy");
                var written = file(runtime,current,new SandboxRuntime.FileRequest(SandboxRuntime.FileAction.WRITE,
                        "a.py", Base64.getEncoder().encodeToString("print(666)\n".getBytes(StandardCharsets.UTF_8))),authority);
                persist(Path.of("/reports/bridge-write-result.json"), JSON.writeValueAsString(written));
                if (written.exitCode() != 0 || !JSON.readTree(written.stdout()).path("ok").asBoolean()) throw new AssertionError("Bridge write");
                var python = runtime.probe(current, UUID.randomUUID(), SandboxRuntime.Probe.PYTHON_666, authority, () -> false);
                var node = runtime.probe(current, UUID.randomUUID(), SandboxRuntime.Probe.NODE_CROSS_READ, authority, () -> false);
                if (python.exitCode() != 0 || node.exitCode() != 0 || !python.stdout().strip().equals("666") || !node.stdout().strip().equals("666")) throw new AssertionError("Execution facts");
                var read = file(runtime,current,new SandboxRuntime.FileRequest(SandboxRuntime.FileAction.READ,
                        "probe-node.txt", null),authority);
                if (!JSON.readTree(read.stdout()).path("contentBase64").asText().equals("NjY2")) throw new AssertionError("Cross-runtime file");
                assertions.add("Python-Node-application-file-port-same-authority");
                var bounded = runtime.probe(current, UUID.randomUUID(), SandboxRuntime.Probe.OUTPUT_BOUND, authority, () -> false);
                if (!bounded.truncated() || bounded.stdout().getBytes(StandardCharsets.UTF_8).length + bounded.stderr().getBytes(StandardCharsets.UTF_8).length > 65536) throw new AssertionError("Output bound");
                assertions.add("bounded-combined-stdout-stderr");
                {
                    var stopped = runtime.probe(current, UUID.randomUUID(), SandboxRuntime.Probe.WAIT_FOR_CANCEL, authority,
                            () -> System.nanoTime() - submittedAt.get() > TimeUnit.SECONDS.toNanos(2));
                    if (!stopped.cancelled() || !stopped.stdout().contains("started") || stopped.stopReceipt() == null
                            || !stopped.stopReceipt().callbacksStopped() || !stopped.stopReceipt().execStopped()) throw new AssertionError("No actual stop receipt");
                    persist(Path.of("/reports/stop-generation1.json"), JSON.writeValueAsString(stopped.stopReceipt()));
                    runtime.removeStopped(current, stopped.stopReceipt());
                    removed = true;
                }
                assertions.add("cancellation-inspect-exec-and-callback-stop-confirmed");
                current = new SandboxRuntime.Identity(environment, space, workspace, UUID.randomUUID(), 2, 2, 200001);
                runtime.prepare(current, authority);
                removed = false;
                var persisted = file(runtime,current,new SandboxRuntime.FileRequest(SandboxRuntime.FileAction.READ,
                        "probe-node.txt", null),authority);
                if (!JSON.readTree(persisted.stdout()).path("contentBase64").asText().equals("NjY2")) throw new AssertionError("Rebuild lost file");
                assertions.add("new-generation-same-workspace-data");
                var stopped = runtime.stop(current);
                persist(Path.of("/reports/stop-generation2.json"), JSON.writeValueAsString(stopped));
                runtime.removeStopped(current, stopped);
                removed = true;
                persist(report, JSON.writeValueAsString(Map.of("status", "PASS", "assertions", assertions,
                        "policyHash", policy.hash(), "supplierCalls", 0, "scope", "Native feasibility; DB/MQ/HTTP/full stage NOT_RUN")));
                System.out.println("PASS native sandbox/file/exec/stop feasibility");
            } finally {
                if (!removed) {
                    // A failure is not permission to force-delete an unconfirmed execution.
                    try {
                        var stopped = runtime.stop(current);
                        persist(Path.of("/reports/failure-stop.json"), JSON.writeValueAsString(stopped));
                        runtime.removeStopped(current, stopped);
                    } catch (Exception retained) { System.err.println("Exact host cleanup required; resource retained"); }
                }
            }
        }
    }
    private static SandboxRuntime.Output file(DockerSandboxRuntime runtime,SandboxRuntime.Identity identity,
            SandboxRuntime.FileRequest request,SandboxRuntime.UseAuthority parent) {
        UUID use=UUID.randomUUID(),runner=UUID.randomUUID();
        var intent=runtime.fileHelperIntent(identity,use);
        final FileHelperData.Snapshot[] current={null};
        var authority=new SandboxRuntime.FileHelperAuthority() {
            @Override public FileHelperData.Intent intent() {return intent;}
            @Override public FileHelperData.Snapshot snapshot() {return current[0];}
            @Override public void created(FileHelperData.Physical physical) {parent.check();current[0]=new FileHelperData.Snapshot(intent,runner,physical,false,null);}
            @Override public void requestStart() {parent.check();current[0]=new FileHelperData.Snapshot(intent,runner,current[0].physical(),true,null);}
            @Override public void started(FileHelperData.Physical physical) {parent.check();current[0]=new FileHelperData.Snapshot(intent,runner,physical,true,null);}
            @Override public void recordExec(FileHelperData.Physical physical) {
                parent.check();persist(Path.of("/reports/file-helper-exec-bindings.jsonl"),JSON.writeValueAsString(Map.of("use",use,"container",physical.containerId(),"exec",physical.execId()))+"\n");
                current[0]=new FileHelperData.Snapshot(intent,runner,physical,true,null);
            }
            @Override public void stopped(FileHelperData.Stopped stop) {current[0]=new FileHelperData.Snapshot(intent,runner,stop.physical(),current[0].startRequested(),stop);}
            @Override public FileHelperData.Stopped retirement() {return current[0]==null?null:current[0].stopped();}
            @Override public void check() {parent.check();}
        };
        try {return runtime.file(identity,use,request,authority,()->false);}
        finally {var receipt=authority.retirement();if(receipt!=null)runtime.removeStoppedFileHelper(identity,use,intent,receipt);}
    }
    private static void persist(Path path, String value) {
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            var bytes = ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        } catch (java.io.IOException error) { throw new IllegalStateException("Probe receipt unavailable", error); }
    }
}
