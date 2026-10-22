package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.workspace.SandboxRuntime;
import com.agentplatform.domain.workspace.WorkspaceFailure;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.json.JsonMapper;

/** Only runner owns this adapter. Creating an exec is not permission to start it. */
public final class DockerSandboxRuntime implements SandboxRuntime {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final DockerClient docker;
    private final SandboxPolicy policy;
    private final com.agentplatform.domain.workspace.WorkspaceEffectBoundary boundary;
    private final UUID instanceId=UUID.randomUUID();
    private final ConcurrentMap<UUID, Capture> active = new ConcurrentHashMap<>();
    private final Semaphore helperSlot=new Semaphore(1);

    public DockerSandboxRuntime(SandboxPolicy policy) {
        this(policy,com.agentplatform.domain.workspace.WorkspaceEffectBoundary.NONE);
    }
    public DockerSandboxRuntime(SandboxPolicy policy,com.agentplatform.domain.workspace.WorkspaceEffectBoundary boundary) {
        this.policy = policy;this.boundary=boundary;
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("unix:///var/run/docker.sock").withApiVersion("1.45").build();
        var transport = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                .connectionTimeout(Duration.ofSeconds(2)).responseTimeout(Duration.ofSeconds(10)).maxConnections(4).build();
        docker = DockerClientImpl.getInstance(config, transport);
    }

    private static WorkspaceFailure unavailable(String code) { return new WorkspaceFailure(503, code); }
    private String name(Identity identity) { return "ap-sandbox-" + identity.environmentId() + "-" + identity.generation(); }
    private Map<String, String> labels(Identity identity) {
        return Map.of("ap.role", "sandbox", "ap.environment", identity.environmentId().toString(),
                "ap.space", identity.spaceId().toString(), "ap.workspace", identity.workspaceId().toString(),
                "ap.command", identity.commandId().toString(), "ap.generation", Long.toString(identity.generation()),
                "ap.fence", Long.toString(identity.fence()), "ap.policy", policy.hash());
    }

    private String[] startup(Identity identity) {
        return new String[]{"--environment", identity.environmentId().toString(), "--space", identity.spaceId().toString(),
                "--workspace", identity.workspaceId().toString(), "--command", identity.commandId().toString(),
                "--generation", Long.toString(identity.generation()), "--fence", Long.toString(identity.fence()),
                "--policy", policy.hash()};
    }

    private String canary(Identity identity) {
        return "r32-ready-v1\n" + identity.environmentId() + "\n" + identity.spaceId() + "\n" + identity.workspaceId()
                + "\n" + identity.commandId() + "\n" + identity.generation() + "\n" + identity.fence() + "\n" + policy.hash() + "\n";
    }

    private String canaryHash(Identity identity) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canary(identity).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void runtime() {
        try {
            var entry = docker.infoCmd().exec().getRuntimes().get("runsc");
            if (entry == null || !SandboxPolicy.RUNSC_PATH.equals(entry.getPath())
                    || Files.isSymbolicLink(policy.runtimeAttestation())) throw unavailable("SANDBOX_RUNTIME_UNAVAILABLE");
            var attestation = JSON.readTree(Files.readString(policy.runtimeAttestation()));
            if (!SandboxPolicy.RUNSC_PATH.equals(attestation.path("path").asText())
                    || !SandboxPolicy.RUNSC_SHA256.equals(attestation.path("sha256").asText())
                    || !attestation.path("runtimeArgs").isArray() || attestation.path("runtimeArgs").size() != 1
                    || !"--platform=systrap".equals(attestation.path("runtimeArgs").get(0).asText())
                    || attestation.path("verifiedAt").asLong() > Instant.now().getEpochSecond() + 5
                    || attestation.path("verifiedAt").asLong() < Instant.now().minusSeconds(86400).getEpochSecond()) {
                throw unavailable("SANDBOX_RUNTIME_UNAVAILABLE");
            }
        } catch (java.io.IOException | NullPointerException invalid) {
            throw unavailable("SANDBOX_RUNTIME_UNAVAILABLE");
        }
    }

    private InspectContainerResponse exact(Identity identity) {
        var found = docker.inspectContainerCmd(name(identity)).exec();
        var host = found.getHostConfig();
        var binds = host.getBinds();
        if (!policy.image().equals(found.getImageId()) || found.getConfig().getLabels() == null
                || !found.getConfig().getLabels().entrySet().containsAll(labels(identity).entrySet())
                || !"10001:10001".equals(found.getConfig().getUser()) || !"runsc".equals(host.getRuntime())
                || !Arrays.equals(new String[]{"python3", "/opt/platform/sandbox-entrypoint.py"}, found.getConfig().getEntrypoint())
                || !Arrays.equals(startup(identity), found.getConfig().getCmd())
                || !Boolean.TRUE.equals(host.getReadonlyRootfs()) || Boolean.TRUE.equals(host.getPrivileged())
                || !"none".equals(host.getNetworkMode()) || !Long.valueOf(SandboxPolicy.MEMORY).equals(host.getMemory())
                || !Long.valueOf(SandboxPolicy.MEMORY).equals(host.getMemorySwap())
                || !Long.valueOf(1_000_000_000L).equals(host.getNanoCPUs()) || !Long.valueOf(128).equals(host.getPidsLimit())
                || host.getDevices() != null && host.getDevices().length != 0
                || host.getCapAdd() != null && host.getCapAdd().length != 0
                || host.getCapDrop() == null || !Arrays.asList(host.getCapDrop()).equals(List.of(Capability.ALL))
                || host.getSecurityOpts() == null || !host.getSecurityOpts().contains("no-new-privileges:true")
                || binds == null || binds.length != 1 || !policy.work(identity).equals(binds[0].getPath())
                || !"/workspace".equals(binds[0].getVolume().getPath()) || binds[0].getAccessMode() != AccessMode.rw
                || host.getTmpFs() == null || !"rw,noexec,nosuid,size=64m,uid=10001,gid=10001,mode=700".equals(host.getTmpFs().get("/tmp"))) {
            throw unavailable("SANDBOX_IDENTITY_MISMATCH");
        }
        return found;
    }

    private Container view(InspectContainerResponse found) {
        return new Container(found.getId(), found.getImageId(), found.getState().getStartedAt(),
                Boolean.TRUE.equals(found.getState().getRunning()), policy.hash(), null);
    }

    @Override public Container inspect(Identity identity) { return view(exact(identity)); }

    @Override public com.agentplatform.domain.workspace.RunnerInstance runnerInstance() {
        String hostname=System.getenv("HOSTNAME");
        if(hostname==null||!hostname.matches("[0-9a-f]{12,64}"))throw unavailable("RUNNER_INSTANCE_UNVERIFIED");
        var self=docker.inspectContainerCmd(hostname).exec();
        if(!Boolean.TRUE.equals(self.getState().getRunning())||self.getConfig().getLabels()==null
                ||!"runner".equals(self.getConfig().getLabels().get("ap.role")))throw unavailable("RUNNER_INSTANCE_UNVERIFIED");
        return new com.agentplatform.domain.workspace.RunnerInstance(instanceId,self.getId(),self.getImageId(),self.getState().getStartedAt());
    }

    @Override public Container verifyReady(Identity identity, Authority authority) {
        authority.check();
        runtime();
        var found = exact(identity);
        if (!Boolean.TRUE.equals(found.getState().getRunning())) throw unavailable("SANDBOX_NOT_RUNNING");
        var capture = new Capture();
        try {
            docker.logContainerCmd(found.getId()).withStdOut(true).withTail(1).exec(capture);
            if (!capture.finished.await(5, TimeUnit.SECONDS) || capture.failure != null || capture.truncated) {
                throw unavailable("SANDBOX_READINESS_UNCONFIRMED");
            }
            var receipt = JSON.readTree(capture.stdout());
            if (receipt == null || !receipt.path("ready").asBoolean() || !identity.commandId().toString().equals(receipt.path("commandId").asText())
                    || !policy.hash().equals(receipt.path("policyHash").asText())
                    || !canaryHash(identity).equals(receipt.path("contentHash").asText())) {
                throw unavailable("SANDBOX_READINESS_UNCONFIRMED");
            }
            verifyStorage(identity, true);
            authority.check();
            var latest = exact(identity);
            if (!found.getId().equals(latest.getId()) || !Boolean.TRUE.equals(latest.getState().getRunning())) {
                throw unavailable("SANDBOX_READINESS_UNCONFIRMED");
            }
            return new Container(latest.getId(), latest.getImageId(), latest.getState().getStartedAt(), true, policy.hash(), canaryHash(identity));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable("SANDBOX_READINESS_UNCONFIRMED");
        } finally {
            try { capture.close(); } catch (java.io.IOException ignored) { /* transport closure is not stop proof */ }
        }
    }

    @Override public Container prepare(Identity identity, Authority authority) {
        authority.check();
        try {
            exact(identity);
            throw unavailable("SANDBOX_EXISTING_INTENT_REQUIRES_QUERY");
        } catch (NotFoundException absent) { /* Only the durable first intent may enter here. */ }
        try {
            runtime();
            verifyStorage(identity);
            authority.check();
        } catch (WorkspaceFailure rejected) {
            // No create request has been sent by this invocation. Recheck absence;
            // unavailable inspect or an existing object cannot become a safe retry.
            try {docker.inspectContainerCmd(name(identity)).exec();}
            catch(NotFoundException absent) {throw new RejectedBeforeEffect(rejected.code());}
            throw rejected;
        }
        var host = HostConfig.newHostConfig().withRuntime("runsc").withNetworkMode("none")
                .withReadonlyRootfs(true).withMemory(SandboxPolicy.MEMORY).withMemorySwap(SandboxPolicy.MEMORY)
                .withNanoCPUs(1_000_000_000L).withPidsLimit(128L).withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of("no-new-privileges:true"))
                .withBinds(new Bind(policy.work(identity), new Volume("/workspace"), AccessMode.rw))
                .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,size=64m,uid=10001,gid=10001,mode=700"))
                .withLogConfig(new LogConfig(LogConfig.LoggingType.JSON_FILE, Map.of("max-size", "10m", "max-file", "3")));
        boundary.await("environment-before-create");
        var created = docker.createContainerCmd(policy.image()).withName(name(identity)).withLabels(labels(identity))
                .withUser("10001:10001").withWorkingDir("/workspace").withCmd(startup(identity)).withHostConfig(host).exec();
        exact(identity);
        boundary.await("environment-created-before-start");
        authority.check();
        docker.startContainerCmd(created.getId()).exec();
        boundary.await("environment-started-before-readiness");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try { return verifyReady(identity, authority); }
            catch (WorkspaceFailure notReady) {
                if (!"SANDBOX_READINESS_UNCONFIRMED".equals(notReady.code()) || System.nanoTime() >= until) throw notReady;
                try { Thread.sleep(100); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw unavailable("SANDBOX_READINESS_UNCONFIRMED");
                }
            }
        }
    }

    private void verifyStorage(Identity identity) { verifyStorage(identity, false); }

    private void verifyStorage(Identity identity, boolean checkCanary) {
        if(!helperSlot.tryAcquire())throw unavailable("WORKSPACE_VERIFIER_BUSY");
        var stopped=new java.util.concurrent.atomic.AtomicBoolean(true);
        try {verifyStorageWithSlot(identity,checkCanary,stopped);}
        finally {if(stopped.get())helperSlot.release();} // Unknown running helper retains its resource slot.
    }

    private void verifyStorageWithSlot(Identity identity, boolean checkCanary,java.util.concurrent.atomic.AtomicBoolean stopped) {
        String verifier = "ap-env-verify-" + UUID.randomUUID();
        String rootHash=RunnerSignatures.sha256(policy.workspaceRoot().getBytes(StandardCharsets.UTF_8));
        if(!docker.listContainersCmd().withShowAll(false).withLabelFilter(Map.of("ap.role","environment-verifier","ap.workspaceRoot",rootHash)).exec().isEmpty()) {
            throw unavailable("WORKSPACE_VERIFIER_UNRESOLVED");
        }
        var host = HostConfig.newHostConfig().withNetworkMode("none").withReadonlyRootfs(true)
                .withMemory(64L * 1024 * 1024).withMemorySwap(64L * 1024 * 1024).withPidsLimit(32L)
                .withCapDrop(Capability.ALL).withCapAdd(Capability.SYS_ADMIN, Capability.CHOWN, Capability.SETUID, Capability.SETGID)
                .withSecurityOpts(List.of("no-new-privileges:true"))
                .withDevices(DockerWorkspaceAllocator.fixedQuotaDevice(policy.quotaDevice()))
                .withBinds(new Bind(policy.workspaceRoot(), new Volume("/quota"), AccessMode.ro))
                .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,size=8m"));
        var arguments = new ArrayList<>(List.of("--space", identity.spaceId().toString(), "--workspace", identity.workspaceId().toString(),
                "--project", Integer.toString(identity.projectId()), "--mount-uuid", policy.mountUuid().toString()));
        if (checkCanary) arguments.addAll(List.of("--canary-command", identity.commandId().toString(), "--canary-hash", canaryHash(identity)));
        var created = docker.createContainerCmd(policy.helperImage()).withName(verifier).withUser("0:0")
                .withLabels(Map.of("ap.role", "environment-verifier", "ap.command", identity.commandId().toString(),"ap.workspaceRoot",rootHash,"ap.runnerInstance",instanceId.toString()))
                .withHostConfig(host).withEntrypoint("python3", "/opt/workspace-quota-verifier.py")
                .withCmd(arguments.toArray(String[]::new)).exec();
        try {
            stopped.set(false);
            docker.startContainerCmd(created.getId()).exec();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < until) {
                var found = docker.inspectContainerCmd(created.getId()).exec();
                if (!Boolean.TRUE.equals(found.getState().getRunning())) {
                    stopped.set(true);
                    var capture = new Capture();
                    docker.logContainerCmd(created.getId()).withStdOut(true).withTail(1).exec(capture);
                    if (!capture.finished.await(5, TimeUnit.SECONDS) || capture.truncated
                            || !Long.valueOf(0).equals(found.getState().getExitCodeLong())
                            || !JSON.readTree(capture.stdout()).path("verified").asBoolean()) {
                        throw unavailable("WORKSPACE_VERIFICATION_UNAVAILABLE");
                    }
                    return;
                }
                Thread.sleep(100);
            }
            throw unavailable("WORKSPACE_VERIFICATION_UNAVAILABLE");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable("WORKSPACE_VERIFICATION_UNAVAILABLE");
        } finally {
            if (stopped.get()) docker.removeContainerCmd(created.getId()).withForce(false).exec();
            // A still-running helper is retained for precise host cleanup; never auto-remove uncertain state.
        }
    }

    @Override public Output file(Identity identity, UUID use, FileRequest request, UseAuthority authority, BooleanSupplier cancelled) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", request.action().name());
        payload.put("path", request.path());
        if (request.contentBase64() != null) payload.put("contentBase64", request.contentBase64());
        byte[] input = (JSON.writeValueAsString(payload) + "\n").getBytes(StandardCharsets.UTF_8);
        if (input.length > 65536) throw new WorkspaceFailure(413, "FILE_REQUEST_TOO_LARGE");
        return execute(identity, use, new String[]{"python3", "/opt/platform/sandbox-file-bridge.py"}, input, authority, cancelled);
    }

    @Override public Output probe(Identity identity, UUID use, Probe probe, UseAuthority authority, BooleanSupplier cancelled) {
        String[] program = switch (probe) {
            case PYTHON_666 -> new String[]{"python3", "-c", "from pathlib import Path;p=Path('/workspace/probe-python.txt');p.write_text('中文😀');assert p.read_text()=='中文😀';print(666)"};
            case NODE_CROSS_READ -> new String[]{"node", "-e", "const fs=require('fs');if(fs.readFileSync('/workspace/probe-python.txt','utf8')!=='中文😀')process.exit(2);fs.writeFileSync('/workspace/probe-node.txt','666');console.log(666)"};
            case WAIT_FOR_CANCEL -> new String[]{"python3", "-c", "import time;print('started',flush=True);time.sleep(180)"};
            case OUTPUT_BOUND -> new String[]{"python3", "-c", "import sys;sys.stdout.write('x'*262144);sys.stderr.write('y'*262144)"};
            case CPU_BOUND,PID_BOUND,MEMORY_BOUND,NETWORK_BOUND,TMP_BOUND,BYTE_QUOTA,INODE_QUOTA,PATH_RACE ->
                    new String[]{"python3","/opt/platform/sandbox-resource-probe.py",probe.name()};
        };
        return execute(identity, use, program, null, authority, cancelled);
    }

    private Output execute(Identity identity, UUID use, String[] program, byte[] input, UseAuthority authority, BooleanSupplier cancelled) {
        Objects.requireNonNull(use);
        authority.check();
        runtime();
        verifyStorage(identity);
        var found = exact(identity);
        if (!Boolean.TRUE.equals(found.getState().getRunning())) throw unavailable("SANDBOX_NOT_RUNNING");
        var capture = new Capture();
        if (active.putIfAbsent(identity.environmentId(), capture) != null) throw new WorkspaceFailure(409, "ENVIRONMENT_USE_CONFLICT");
        boolean completed = false;
        try {
            authority.check();
            String exec = docker.execCreateCmd(found.getId()).withAttachStdout(true).withAttachStderr(true)
                    .withAttachStdin(input != null).withTty(false).withPrivileged(false).withUser("10001:10001")
                    .withWorkingDir("/workspace").withEnv(List.of("PLATFORM_USE_ID=" + use)).withCmd(program).exec().getId();
            capture.execId = exec;
            authority.recordExec(found.getId(), exec);
            authority.check();
            var start = docker.execStartCmd(exec).withDetach(false).withTty(false);
            if (input != null) start.withStdIn(new ByteArrayInputStream(input));
            synchronized (capture) {
                // A stop before start must prohibit every later start, not merely
                // count down a latch while the execution thread can still start.
                if (capture.stopRequested) throw unavailable("SANDBOX_STOP_REQUESTED");
                capture.startRequested = true;
            }
            start.exec(capture);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (true) {
                boolean cancelledNow = cancelled.getAsBoolean();
                boolean timedOut = System.nanoTime() >= until;
                if (cancelledNow || timedOut) {
                    Stop stopped = stop(identity);
                    completed = true;
                    return capture.output(-1, timedOut, cancelledNow, stopped);
                }
                authority.check();
                var state = docker.inspectExecCmd(exec).exec();
                if (Boolean.FALSE.equals(state.isRunning()) && capture.finished.getCount() == 0) {
                    if (capture.failure != null || state.getExitCodeLong() == null) throw unavailable("SANDBOX_EXEC_RESULT_UNKNOWN");
                    authority.check();
                    if(!Boolean.TRUE.equals(exact(identity).getState().getRunning()))throw unavailable("SANDBOX_CONTAINER_EXITED");
                    completed = true;
                    return capture.output(state.getExitCodeLong(), false, false, null);
                }
                Thread.sleep(250);
            }
        } catch (InterruptedException interrupted) {
            Stop stopped = stop(identity);
            completed = true;
            Thread.currentThread().interrupt();
            return capture.output(-1, false, true, stopped);
        } catch (RuntimeException failure) {
            Stop stopped = stop(identity);
            completed = true;
            throw new Terminated(stopped, failure);
        } finally {
            // A failed stop retains this local barrier; callers cannot start a replacement use.
            if (completed) active.remove(identity.environmentId(), capture);
        }
    }

    @Override public Stop stop(Identity identity) {
        var found = exact(identity);
        Capture capture = active.get(identity.environmentId());
        if (capture != null) synchronized (capture) {
            capture.stopRequested = true;
            if (!capture.startRequested) capture.finished.countDown();
        }
        if (Boolean.TRUE.equals(found.getState().getRunning())) {
            docker.stopContainerCmd(found.getId()).withTimeout(2).exec();
        }
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            found = exact(identity);
            boolean execStopped = capture == null || capture.execId == null
                    || Boolean.FALSE.equals(docker.inspectExecCmd(capture.execId).exec().isRunning());
            boolean callbacksStopped = capture == null || capture.finished.getCount() == 0;
            if (!Boolean.TRUE.equals(found.getState().getRunning()) && execStopped && callbacksStopped
                    && found.getState().getFinishedAt() != null && !found.getState().getFinishedAt().startsWith("0001")) {
                return new Stop(found.getId(), found.getImageId(), found.getState().getStartedAt(),
                        found.getState().getFinishedAt(), true, true, Instant.now());
            }
            try { Thread.sleep(50); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        } while (System.nanoTime() < until);
        throw unavailable("SANDBOX_STOP_UNCONFIRMED");
    }

    @Override public void removeStopped(Identity identity, Stop receipt) {
        var found = exact(identity);
        if (!receipt.execStopped() || !receipt.callbacksStopped() || !receipt.containerId().equals(found.getId())
                || !receipt.image().equals(found.getImageId()) || !receipt.startedAt().equals(found.getState().getStartedAt())
                || !receipt.finishedAt().equals(found.getState().getFinishedAt()) || Boolean.TRUE.equals(found.getState().getRunning())) {
            throw unavailable("SANDBOX_STOP_IDENTITY_MISMATCH");
        }
        docker.removeContainerCmd(found.getId()).withForce(false).withRemoveVolumes(false).exec();
    }

    @Override public void close() throws java.io.IOException { docker.close(); }

    private static final class Capture extends ResultCallback.Adapter<Frame> {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile String execId;
        private volatile boolean startRequested;
        private volatile boolean stopRequested;
        private volatile boolean truncated;
        private volatile Throwable failure;
        @Override public synchronized void onNext(Frame frame) {
            int remaining = SandboxPolicy.OUTPUT_BYTES - out.size() - err.size();
            byte[] bytes = frame.getPayload();
            if (bytes.length > remaining) truncated = true;
            var destination = frame.getStreamType() == StreamType.STDERR ? err : out;
            destination.write(bytes, 0, Math.min(bytes.length, remaining));
        }
        @Override public void onComplete() {
            try { super.onComplete(); } finally { finished.countDown(); }
        }
        @Override public void onError(Throwable error) {
            failure = error;
            try { super.onError(error); } finally { finished.countDown(); }
        }
        synchronized String stdout() { return out.toString(StandardCharsets.UTF_8); }
        synchronized Output output(long exit, boolean timeout, boolean cancelled, Stop stopped) {
            return new Output(execId, exit, stdout(), err.toString(StandardCharsets.UTF_8), truncated, timeout, cancelled, stopped);
        }
    }
}
