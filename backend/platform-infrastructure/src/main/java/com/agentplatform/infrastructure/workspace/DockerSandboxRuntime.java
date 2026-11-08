package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.workspace.SandboxRuntime;
import com.agentplatform.domain.workspace.FileHelperData;
import com.agentplatform.domain.workspace.FileHelperData.Intent;
import com.agentplatform.domain.workspace.FileHelperData.Physical;
import com.agentplatform.domain.workspace.FileHelperData.Snapshot;
import com.agentplatform.domain.workspace.FileHelperData.Stopped;
import com.agentplatform.domain.workspace.WorkspaceFailure;
import com.agentplatform.domain.capability.CapabilityMaterialization;
import com.agentplatform.domain.capability.CapabilityMaterializationCommand;
import com.agentplatform.domain.capability.CapabilityMaterializationReceipt;
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
    private final UUID instanceId;
    private final ConcurrentMap<UUID, Capture> active = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Capture> helperActive = new ConcurrentHashMap<>();
    private final AuxiliaryContainerSlot helperSlot;

    public DockerSandboxRuntime(SandboxPolicy policy) {
        this(policy,com.agentplatform.domain.workspace.WorkspaceEffectBoundary.NONE);
    }
    public DockerSandboxRuntime(SandboxPolicy policy,com.agentplatform.domain.workspace.WorkspaceEffectBoundary boundary) {
        this(policy,boundary,UUID.randomUUID(),new AuxiliaryContainerSlot());
    }
    public DockerSandboxRuntime(SandboxPolicy policy,com.agentplatform.domain.workspace.WorkspaceEffectBoundary boundary,
            UUID instanceId,AuxiliaryContainerSlot helperSlot) {
        this(policy,boundary,instanceId,helperSlot,createDockerClient());
    }
    DockerSandboxRuntime(SandboxPolicy policy,com.agentplatform.domain.workspace.WorkspaceEffectBoundary boundary,
            UUID instanceId,AuxiliaryContainerSlot helperSlot,DockerClient client) {
        this.instanceId=Objects.requireNonNull(instanceId);this.helperSlot=Objects.requireNonNull(helperSlot);
        this.policy = Objects.requireNonNull(policy);this.boundary=Objects.requireNonNull(boundary);
        this.docker=Objects.requireNonNull(client);
    }
    private static DockerClient createDockerClient() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("unix:///var/run/docker.sock").withApiVersion("1.45").build();
        var transport = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                .connectionTimeout(Duration.ofSeconds(2)).responseTimeout(Duration.ofSeconds(10)).maxConnections(4).build();
        return DockerClientImpl.getInstance(config, transport);
    }

    private static WorkspaceFailure unavailable(String code) { return new WorkspaceFailure(503, code); }
    /** An exec404 is only an observation gap after its exact parent has ended. */
    static boolean confirmedContainerStopped(InspectContainerResponse.ContainerState state) {
        if(state==null||!"exited".equals(state.getStatus())||!Boolean.FALSE.equals(state.getRunning())
                ||!Boolean.FALSE.equals(state.getPaused())||!Boolean.FALSE.equals(state.getRestarting())
                ||!Long.valueOf(0).equals(state.getPidLong()))return false;
        try {
            Instant start=Instant.parse(state.getStartedAt()),finish=Instant.parse(state.getFinishedAt());
            return start.isAfter(Instant.EPOCH)&&finish.isAfter(start);
        } catch(RuntimeException invalid) {return false;}
    }
    private boolean execStopped(InspectContainerResponse parent,String execId) {
        if(execId==null)return true;
        try {return Boolean.FALSE.equals(docker.inspectExecCmd(execId).exec().isRunning());}
        catch(NotFoundException retired) {return confirmedContainerStopped(parent.getState());}
    }
    private String name(Identity identity) { return "ap-sandbox-" + identity.environmentId() + "-" + identity.generation(); }
    private static String helperName(UUID useId) {return "ap-file-helper-"+useId;}
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
                || !SandboxPolicy.guestProcessLimitMatches(host.getUlimits())
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

    @Override public Intent fileHelperIntent(Identity identity,UUID useId) {
        Objects.requireNonNull(identity);Objects.requireNonNull(useId);
        return new Intent(useId,policy.helperImage(),policy.hash(),helperName(useId));
    }

    private Map<String,String> helperLabels(Identity identity,UUID useId) {
        return Map.of("ap.role","file-helper","ap.use",useId.toString(),"ap.environment",identity.environmentId().toString(),
                "ap.space",identity.spaceId().toString(),"ap.workspace",identity.workspaceId().toString(),"ap.command",identity.commandId().toString(),
                "ap.generation",Long.toString(identity.generation()),"ap.fence",Long.toString(identity.fence()),"ap.policy",policy.hash());
    }

    private InspectContainerResponse exactFileHelper(Identity identity,Intent intent,String identifier) {
        var found=docker.inspectContainerCmd(identifier).exec();var host=found.getHostConfig();var binds=host.getBinds();
        var expectedLabels=helperLabels(identity,intent.useId());
        String runtime=host.getRuntime();
        if(!intent.containerName().equals(found.getName().replaceFirst("^/",""))||!policy.helperImage().equals(found.getImageId())
                ||found.getConfig().getLabels()==null||!found.getConfig().getLabels().entrySet().containsAll(expectedLabels.entrySet())
                ||!"10001:10001".equals(found.getConfig().getUser())
                ||!Arrays.equals(new String[]{"python3","/opt/platform/file-helper-entrypoint.py"},found.getConfig().getEntrypoint())
                ||!Arrays.equals(new String[]{"idle"},found.getConfig().getCmd())
                ||Boolean.TRUE.equals(host.getPrivileged())||!Boolean.TRUE.equals(host.getReadonlyRootfs())
                ||!(runtime==null||"runc".equals(runtime))||!"none".equals(host.getNetworkMode())
                ||!Long.valueOf(64L*1024*1024).equals(host.getMemory())||!Long.valueOf(64L*1024*1024).equals(host.getMemorySwap())
                ||!Long.valueOf(1_000_000_000L).equals(host.getNanoCPUs())||!Long.valueOf(32).equals(host.getPidsLimit())
                ||host.getDevices()!=null&&host.getDevices().length!=0||host.getCapAdd()!=null&&host.getCapAdd().length!=0
                ||host.getCapDrop()==null||!Arrays.asList(host.getCapDrop()).equals(List.of(Capability.ALL))
                ||host.getSecurityOpts()==null||!host.getSecurityOpts().contains("no-new-privileges:true")
                ||binds==null||binds.length!=1||!policy.work(identity).equals(binds[0].getPath())
                ||!"/workspace".equals(binds[0].getVolume().getPath())||binds[0].getAccessMode()!=AccessMode.rw
                ||host.getTmpFs()==null||!"rw,noexec,nosuid,size=8m,uid=10001,gid=10001,mode=700".equals(host.getTmpFs().get("/tmp")))
            throw unavailable("FILE_HELPER_IDENTITY_MISMATCH");
        return found;
    }

    private String startedAt(InspectContainerResponse found) {
        String value=found.getState().getStartedAt();return value==null||value.startsWith("0001")?null:value;
    }
    private Physical physical(InspectContainerResponse found,String execId) {
        return new Physical(found.getId(),found.getImageId(),startedAt(found),execId);
    }

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
                .withUlimits(List.of(new Ulimit("nproc", SandboxPolicy.GUEST_NPROC, SandboxPolicy.GUEST_NPROC)))
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
        var permit=helperSlot.tryAcquire();
        if(permit==null)throw unavailable("WORKSPACE_VERIFIER_BUSY");
        var stopped=new java.util.concurrent.atomic.AtomicBoolean(true);
        try {verifyStorageWithSlot(identity,checkCanary,stopped);}
        finally {
            if(stopped.get())helperSlot.release(permit);
            else helperSlot.retain(permit,null); // A file query cannot resolve an unknown verifier.
        }
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

    @Override public Output file(Identity identity,UUID use,FileRequest request,FileHelperAuthority authority,BooleanSupplier cancelled) {
        Objects.requireNonNull(use);Objects.requireNonNull(request);Objects.requireNonNull(authority);Objects.requireNonNull(cancelled);
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("action",request.action().name());payload.put("path",request.path());
        if(request.contentBase64()!=null)payload.put("contentBase64",request.contentBase64());
        byte[] input=(JSON.writeValueAsString(payload)+"\n").getBytes(StandardCharsets.UTF_8);
        if(input.length>65536)throw new WorkspaceFailure(413,"FILE_REQUEST_TOO_LARGE");
        if(!authority.intent().useId().equals(use)||!policy.hash().equals(authority.intent().policyHash())
                ||!policy.helperImage().equals(authority.intent().imageId()))throw unavailable("FILE_HELPER_IDENTITY_MISMATCH");
        var permit=helperSlot.tryAcquire();
        if(permit==null)throw unavailable("WORKSPACE_HELPER_BUSY");
        boolean releaseSlot=false;
        var verifierStopped=new java.util.concurrent.atomic.AtomicBoolean(true);
        try {
            authority.check();runtime();
            var sandbox=exact(identity);
            if(!Boolean.TRUE.equals(sandbox.getState().getRunning()))throw unavailable("SANDBOX_NOT_RUNNING");
            verifyStorageWithSlot(identity,false,verifierStopped);
            Snapshot snapshot=authority.snapshot();
            if(snapshot==null||!snapshot.intent().equals(authority.intent()))throw unavailable("FILE_HELPER_INTENT_MISSING");
            if(snapshot.stopped()!=null)throw unavailable("FILE_HELPER_ALREADY_STOPPED");
            InspectContainerResponse helper=resolveFileHelper(identity,use,authority);
            snapshot=authority.snapshot();
            if(snapshot==null||snapshot.physical()==null)throw unavailable("FILE_HELPER_IDENTITY_MISMATCH");
            if(snapshot.physical().startedAt()==null) {
                if(snapshot.startRequested())throw unavailable("FILE_HELPER_START_UNKNOWN");
                authority.requestStart();
                try {docker.startContainerCmd(snapshot.physical().containerId()).exec();}
                catch(RuntimeException lostStartResponse) { /* Inspect the same named helper before deciding. */ }
                helper=exactFileHelper(identity,authority.intent(),snapshot.physical().containerId());
                String actualStart=startedAt(helper);
                if(actualStart==null||!Boolean.TRUE.equals(helper.getState().getRunning()))throw unavailable("FILE_HELPER_START_UNCONFIRMED");
                authority.started(physical(helper,null));
            } else {
                helper=exactFileHelper(identity,authority.intent(),snapshot.physical().containerId());
                if(!Boolean.TRUE.equals(helper.getState().getRunning()))throw unavailable("FILE_HELPER_NOT_RUNNING");
            }
            snapshot=authority.snapshot();
            if(snapshot.physical().execId()!=null)throw unavailable("FILE_HELPER_RESULT_UNKNOWN");
            authority.check();
            String exec=docker.execCreateCmd(helper.getId()).withAttachStdout(true).withAttachStderr(true).withAttachStdin(true)
                    .withTty(false).withPrivileged(false).withUser("10001:10001").withWorkingDir("/workspace")
                    .withEnv(List.of("PLATFORM_USE_ID="+use)).withCmd("python3","-I","/opt/platform/sandbox-file-bridge.py").exec().getId();
            Physical execIdentity=physical(helper,exec);
            authority.recordExec(execIdentity);
            authority.check();
            var capture=new Capture();capture.execId=exec;
            if(helperActive.putIfAbsent(use,capture)!=null)throw new WorkspaceFailure(409,"FILE_HELPER_USE_CONFLICT");
            boolean timedOut=false,cancelledNow=false;long exit=-1;
            try {
                boundary.await("file-helper-before-exec-start");
                docker.execStartCmd(exec).withDetach(false).withTty(false).withStdIn(new ByteArrayInputStream(input)).exec(capture);
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
                while(true) {
                    cancelledNow=cancelled.getAsBoolean();timedOut=System.nanoTime()>=until;
                    if(cancelledNow||timedOut)break;
                    authority.check();
                    var state=docker.inspectExecCmd(exec).exec();
                    if(Boolean.FALSE.equals(state.isRunning())&&capture.finished.getCount()==0) {
                        if(capture.failure!=null||state.getExitCodeLong()==null)throw unavailable("FILE_HELPER_RESULT_UNKNOWN");
                        exit=state.getExitCodeLong();break;
                    }
                    Thread.sleep(100);
                }
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();cancelledNow=true;
            }
            Output captured=capture.output(exit,timedOut,cancelledNow,null);
            stopFileHelperInternal(identity,use,authority);
            releaseSlot=true;
            if(timedOut||cancelledNow) {
                Stop sandboxStop=stop(identity);
                return new Output(captured.execId(),-1,captured.stdout(),captured.stderr(),captured.truncated(),timedOut,cancelledNow,sandboxStop);
            }
            authority.check();
            return new Output(captured.execId(),exit,captured.stdout(),captured.stderr(),captured.truncated(),false,false,null);
        } catch(RuntimeException failure) {
            try {
                Stopped stopped=stopFileHelperInternal(identity,use,authority);
                if(stopped!=null)releaseSlot=true;
            } catch(RuntimeException unconfirmed) { /* Keep the shared helper slot and the use unresolved. */ }
            throw failure;
        } finally {
            if(releaseSlot&&verifierStopped.get()) {
                helperActive.remove(use);
                helperSlot.release(permit);
            } else helperSlot.retain(permit,verifierStopped.get()?use:null);
        }
    }

    private InspectContainerResponse resolveFileHelper(Identity identity,UUID use,FileHelperAuthority authority) {
        Intent intent=authority.intent();Snapshot snapshot=authority.snapshot();
        if(snapshot==null)throw unavailable("FILE_HELPER_INTENT_MISSING");
        if(snapshot.physical()!=null) {
            var found=exactFileHelper(identity,intent,snapshot.physical().containerId());
            if(!Objects.equals(snapshot.physical().startedAt(),startedAt(found))&&snapshot.physical().startedAt()!=null)
                throw unavailable("FILE_HELPER_IDENTITY_MISMATCH");
            return found;
        }
        InspectContainerResponse found;
        try {found=exactFileHelper(identity,intent,intent.containerName());}
        catch(NotFoundException absent) {
            if(snapshot.startRequested())throw unavailable("FILE_HELPER_START_UNKNOWN");
            authority.check();
            var host=HostConfig.newHostConfig().withNetworkMode("none").withReadonlyRootfs(true)
                    .withMemory(64L*1024*1024).withMemorySwap(64L*1024*1024).withNanoCPUs(1_000_000_000L).withPidsLimit(32L)
                    .withCapDrop(Capability.ALL).withSecurityOpts(List.of("no-new-privileges:true"))
                    .withBinds(new Bind(policy.work(identity),new Volume("/workspace"),AccessMode.rw))
                    .withTmpFs(Map.of("/tmp","rw,noexec,nosuid,size=8m,uid=10001,gid=10001,mode=700"))
                    .withLogConfig(new LogConfig(LogConfig.LoggingType.JSON_FILE,Map.of("max-size","10m","max-file","3")));
            try {
                docker.createContainerCmd(policy.helperImage()).withName(intent.containerName()).withLabels(helperLabels(identity,use))
                        .withUser("10001:10001").withWorkingDir("/workspace").withEntrypoint("python3","/opt/platform/file-helper-entrypoint.py")
                        .withCmd("idle").withHostConfig(host).exec();
            } catch(RuntimeException lostCreateResponse) { /* The use-derived name is the only recovery key. */ }
            found=exactFileHelper(identity,intent,intent.containerName());
            if(startedAt(found)!=null)throw unavailable("FILE_HELPER_UNEXPECTED_START");
            authority.created(physical(found,null));
            return found;
        }
        if(startedAt(found)!=null||Boolean.TRUE.equals(found.getState().getRunning()))throw unavailable("FILE_HELPER_UNEXPECTED_START");
        authority.created(physical(found,null));
        return found;
    }

    @Override public Stopped stopFileHelper(Identity identity,UUID use,FileHelperAuthority authority) {
        var retained=helperSlot.tryReconcile(use);
        var permit=retained==null?helperSlot.tryAcquire():retained;
        if(permit==null)throw unavailable("WORKSPACE_HELPER_BUSY");
        boolean released=false;
        try {
            Stopped receipt=stopFileHelperInternal(identity,use,authority);
            // A missing durable intent cannot have authorized helper creation.
            released=true;
            return receipt;
        } finally {
            if(released) {
                helperActive.remove(use);
                helperSlot.release(permit);
            } else helperSlot.retain(permit,use);
        }
    }

    private Stopped stopFileHelperInternal(Identity identity,UUID use,FileHelperAuthority authority) {
        Snapshot snapshot=authority.snapshot();if(snapshot==null)return null;
        if(snapshot.stopped()!=null)return snapshot.stopped();
        Intent intent=snapshot.intent();InspectContainerResponse found=null;
        String identifier=snapshot.physical()==null?intent.containerName():snapshot.physical().containerId();
        try {found=exactFileHelper(identity,intent,identifier);}
        catch(NotFoundException absent) {
            if(snapshot.physical()!=null||snapshot.startRequested())throw unavailable("FILE_HELPER_STOP_UNCONFIRMED");
            var receipt=new Stopped(null,intent.imageId(),true,null,true,true,Instant.now());
            authority.stopped(receipt);return receipt;
        }
        if(snapshot.physical()==null) {
            if(snapshot.startRequested()||startedAt(found)!=null||Boolean.TRUE.equals(found.getState().getRunning()))throw unavailable("FILE_HELPER_STOP_UNCONFIRMED");
            authority.created(physical(found,null));
            snapshot=authority.snapshot();
        }
        String actualStart=startedAt(found);
        if(snapshot.physical().startedAt()==null&&actualStart!=null) {
            if(!snapshot.startRequested())throw unavailable("FILE_HELPER_STOP_UNCONFIRMED");
            authority.started(physical(found,null));snapshot=authority.snapshot();
        }
        if(snapshot.startRequested()&&snapshot.physical().startedAt()==null)throw unavailable("FILE_HELPER_STOP_UNCONFIRMED");
        Capture capture=helperActive.get(use);
        if(Boolean.TRUE.equals(found.getState().getRunning()))docker.stopContainerCmd(found.getId()).withTimeout(2).exec();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do {
            found=exactFileHelper(identity,intent,snapshot.physical().containerId());
            if(snapshot.physical().startedAt()!=null&&!snapshot.physical().startedAt().equals(startedAt(found)))
                throw unavailable("FILE_HELPER_STOP_IDENTITY_MISMATCH");
            boolean execStopped=execStopped(found,snapshot.physical().execId());
            boolean callbacksStopped=capture==null||capture.finished.getCount()==0;
            if((startedAt(found)==null&&!Boolean.TRUE.equals(found.getState().getRunning())&&Long.valueOf(0).equals(found.getState().getPidLong())
                    ||confirmedContainerStopped(found.getState()))&&execStopped&&callbacksStopped) {
                String start=startedAt(found),finished=found.getState().getFinishedAt();
                if(snapshot.startRequested()&&start==null||start!=null&&(finished==null||finished.startsWith("0001")))throw unavailable("FILE_HELPER_STOP_UNCONFIRMED");
                var physical=physical(found,snapshot.physical().execId());
                var receipt=new Stopped(physical,intent.imageId(),start==null, start==null?null:finished,execStopped,callbacksStopped,Instant.now());
                authority.stopped(receipt);return receipt;
            }
            try {Thread.sleep(50);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}
        } while(System.nanoTime()<until);
        throw unavailable("FILE_HELPER_STOP_UNCONFIRMED");
    }

    @Override public void removeStoppedFileHelper(Identity identity,UUID use,Intent intent,Stopped receipt) {
        if(receipt==null||!receipt.execStopped()||!receipt.callbacksStopped())throw unavailable("FILE_HELPER_STOP_UNCONFIRMED");
        if(receipt.physical()==null)return;
        var found=exactFileHelper(identity,intent,receipt.physical().containerId());
        if(Boolean.TRUE.equals(found.getState().getRunning())||!Objects.equals(startedAt(found),receipt.physical().startedAt())
                ||!Objects.equals(found.getState().getFinishedAt(),receipt.finishedAt())||!Objects.equals(found.getImageId(),receipt.imageId()))
            throw unavailable("FILE_HELPER_STOP_MISMATCH");
        docker.removeContainerCmd(found.getId()).withForce(false).withRemoveVolumes(false).exec();
    }

    @Override public Output probe(Identity identity, UUID use, Probe probe, UseAuthority authority, BooleanSupplier cancelled) {
        String[] program = switch (probe) {
            case PYTHON_666 -> new String[]{"python3", "-c", "from pathlib import Path;p=Path('/workspace/probe-python.txt');p.write_text('中文😀');assert p.read_text()=='中文😀';print(666)"};
            case NODE_CROSS_READ -> new String[]{"node", "-e", "const fs=require('fs');if(fs.readFileSync('/workspace/probe-python.txt','utf8')!=='中文😀')process.exit(2);fs.writeFileSync('/workspace/probe-node.txt','666');console.log(666)"};
            case WAIT_FOR_CANCEL -> new String[]{"python3", "-c", "import time;print('started',flush=True);time.sleep(180)"};
            case OUTPUT_BOUND -> new String[]{"python3", "-c", "import sys;sys.stdout.write('x'*262144);sys.stderr.write('y'*262144)"};
            case CPU_BOUND,PID_BOUND,MEMORY_BOUND,NETWORK_BOUND,TMP_BOUND,BYTE_QUOTA,INODE_QUOTA ->
                    new String[]{"python3","/opt/platform/sandbox-resource-probe.py",probe.name()};
        };
        return execute(identity, use, program, null, authority, cancelled);
    }

    @Override public Output shell(Identity identity,UUID use,ShellRequest request,UseAuthority authority,BooleanSupplier cancelled) {
        Objects.requireNonNull(request);
        return execute(identity,use,new String[]{"python3","/opt/platform/sandbox-shell.py",request.cwd(),request.command()},
                null,authority,cancelled,request.timeoutSeconds(),true);
    }

    private Output execute(Identity identity, UUID use, String[] program, byte[] input, UseAuthority authority, BooleanSupplier cancelled) {
        return execute(identity,use,program,input,authority,cancelled,120,false);
    }

    private Output execute(Identity identity, UUID use, String[] program, byte[] input, UseAuthority authority, BooleanSupplier cancelled,int timeoutSeconds,boolean stopAfter) {
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
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
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
                if(stopAfter&&Boolean.FALSE.equals(state.isRunning())&&state.getExitCodeLong()!=null) {
                    // Descendants may retain stdout/stderr after the shell process
                    // exits. Stop the exact container before awaiting stream EOF;
                    // waiting for EOF first would let a background process keep
                    // the command alive until its timeout. stop() proves both.
                    authority.check();
                    Stop stopped=stop(identity);
                    if(capture.failure!=null)throw unavailable("SANDBOX_EXEC_RESULT_UNKNOWN");
                    completed=true;
                    return capture.output(state.getExitCodeLong(),false,false,stopped);
                }
                if (Boolean.FALSE.equals(state.isRunning()) && capture.finished.getCount() == 0) {
                    if (capture.failure != null || state.getExitCodeLong() == null) throw unavailable("SANDBOX_EXEC_RESULT_UNKNOWN");
                    authority.check();
                    if(!Boolean.TRUE.equals(exact(identity).getState().getRunning()))throw unavailable("SANDBOX_CONTAINER_EXITED");
                    // Shell descendants can outlive the parent exec. Only stopping the
                    // entire exact generation provides a sealing boundary for its files.
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
            boolean execStopped = capture == null || execStopped(found,capture.execId);
            boolean callbacksStopped = capture == null || capture.finished.getCount() == 0;
            if (confirmedContainerStopped(found.getState()) && execStopped && callbacksStopped
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

    @Override public Removal confirmRemoval(Identity identity, Stop receipt) {
        Objects.requireNonNull(identity);
        if(receipt==null||!receipt.execStopped()||!receipt.callbacksStopped()
                ||!policy.image().equals(receipt.image())||receipt.containerId()==null
                ||!receipt.containerId().matches("[0-9a-f]{64}")||receipt.observedAt()==null)
            throw unavailable("SANDBOX_STOP_UNCONFIRMED");
        try {
            var start=Instant.parse(receipt.startedAt());var finish=Instant.parse(receipt.finishedAt());
            if(!start.isAfter(Instant.EPOCH)||!finish.isAfter(start)||receipt.observedAt().isBefore(finish))
                throw new IllegalArgumentException();
        } catch(RuntimeException invalid) {throw unavailable("SANDBOX_STOP_UNCONFIRMED");}
        try {
            // This validates all original labels, mounts and isolation, then the
            // precise stopped identity; force and volume removal remain disabled.
            removeStopped(identity,receipt);
        } catch(NotFoundException alreadyRemoved) {
            // A lost remove response can be queried using the committed stop.
            // Absence alone, without that prior fact, never reaches this branch.
        }
        for(String target:List.of(receipt.containerId(),name(identity))) {
            try {
                docker.inspectContainerCmd(target).exec();
                throw unavailable("SANDBOX_CLEANUP_UNCONFIRMED");
            } catch(NotFoundException absent) { /* Both exact ID and original name must be absent. */ }
        }
        return new Removal(receipt.containerId(),receipt.image(),receipt.startedAt(),receipt.finishedAt(),Instant.now());
    }

    /**
     * Starts a dedicated, fixed canary container with the current workspace and only the
     * selected manifest roots. It never reuses an environment whose bind set is immutable.
     */
    public CapabilityMaterializationReceipt verifyCapabilityMounts(CapabilityMaterializationCommand command,
            CapabilityMaterialization.Manifest manifest,Map<String,java.nio.file.Path> materialRoots,
            String capabilityPolicyHash,Runnable authority) {
        Objects.requireNonNull(command);Objects.requireNonNull(manifest);Objects.requireNonNull(materialRoots);Objects.requireNonNull(authority);
        if(!command.spaceId().equals(manifest.spaceId())||!command.taskId().equals(manifest.taskId())
                ||!command.manifestHash().equals(manifest.hash())||capabilityPolicyHash==null
                ||!capabilityPolicyHash.matches("[a-f0-9]{64}")||manifest.versions().isEmpty()
                ||materialRoots.size()!=manifest.versions().size())throw unavailable("CAPABILITY_MATERIALIZATION_MANIFEST_MISMATCH");
        runtime();authority.run();
        var identity=new Identity(command.operationId(),command.spaceId(),command.workspaceId(),command.commandId(),
                command.generation(),command.fence(),200001);
        java.nio.file.Path work=java.nio.file.Path.of(policy.work(identity)).toAbsolutePath().normalize();
        if(java.nio.file.Files.isSymbolicLink(work)||!java.nio.file.Files.isDirectory(work,java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw unavailable("CAPABILITY_WORKSPACE_UNAVAILABLE");
        try {
            if(!work.toRealPath().equals(work))throw unavailable("CAPABILITY_WORKSPACE_UNAVAILABLE");
        } catch(java.io.IOException invalid){throw unavailable("CAPABILITY_WORKSPACE_UNAVAILABLE");}
        java.nio.file.Path materialBase=java.nio.file.Path.of(policy.workspaceRoot()).resolve(".platform-capability-materials").toAbsolutePath().normalize();
        var expected=new LinkedHashMap<String,java.nio.file.Path>();
        for(var version:manifest.versions()) {
            var root=materialRoots.get(version.mountTarget());
            if(root==null)throw unavailable("CAPABILITY_MATERIALIZATION_ROOT_MISMATCH");
            try {
                var normalized=root.toAbsolutePath().normalize();
                if(!normalized.startsWith(materialBase)||java.nio.file.Files.isSymbolicLink(normalized)
                        ||!java.nio.file.Files.isDirectory(normalized,java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        ||!normalized.toRealPath().equals(normalized))throw unavailable("CAPABILITY_MATERIALIZATION_ROOT_MISMATCH");
                expected.put(version.mountTarget(),normalized);
            } catch(java.io.IOException invalid){throw unavailable("CAPABILITY_MATERIALIZATION_ROOT_MISMATCH");}
        }
        if(expected.size()!=materialRoots.size())throw unavailable("CAPABILITY_MATERIALIZATION_ROOT_MISMATCH");
        String physicalName="ap-capability-material-"+command.operationId();
        String image=policy.image();String runtimePolicy=RunnerSignatures.sha256((policy.hash()+"|capability-materialization-v1|"
                +capabilityPolicyHash+"|"+manifest.hash()+"|workspace-rw|selected-ro|runsc-systrap|net-none|uid10001|caps-none|memory512m-swap512m|cpu1|pids128")
                .getBytes(StandardCharsets.UTF_8));
        var labels=Map.of("ap.role","capability-materialization","ap.operation",command.operationId().toString(),
                "ap.command",command.commandId().toString(),"ap.task",command.taskId().toString(),"ap.space",command.spaceId().toString(),
                "ap.workspace",command.workspaceId().toString(),"ap.generation",Long.toString(command.generation()),
                "ap.fence",Long.toString(command.fence()),"ap.manifest",manifest.hash(),"ap.policy",runtimePolicy);
        try {docker.inspectContainerCmd(physicalName).exec();throw unavailable("CAPABILITY_MATERIALIZATION_EXISTING_REQUIRES_QUERY");}
        catch(NotFoundException absent) { /* A unique command may create its exact container once. */ }
        var binds=new ArrayList<Bind>();
        binds.add(new Bind(work.toString(),new Volume("/workspace"),AccessMode.rw));
        expected.forEach((target,root)->binds.add(new Bind(root.toString(),new Volume(target),AccessMode.ro)));
        var host=HostConfig.newHostConfig().withRuntime("runsc").withNetworkMode("none").withReadonlyRootfs(true)
                .withMemory(SandboxPolicy.MEMORY).withMemorySwap(SandboxPolicy.MEMORY).withNanoCPUs(1_000_000_000L)
                .withPidsLimit(128L).withCapDrop(Capability.ALL).withUlimits(List.of(new Ulimit("nproc",SandboxPolicy.GUEST_NPROC,SandboxPolicy.GUEST_NPROC)))
                .withSecurityOpts(List.of("no-new-privileges:true")).withBinds(binds.toArray(Bind[]::new))
                .withTmpFs(Map.of("/tmp","rw,noexec,nosuid,size=64m,uid=10001,gid=10001,mode=700"))
                .withLogConfig(new LogConfig(LogConfig.LoggingType.JSON_FILE,Map.of("max-size","10m","max-file","3")));
        var created=docker.createContainerCmd(image).withName(physicalName).withLabels(labels).withUser("10001:10001")
                .withWorkingDir("/workspace").withEntrypoint("python3","-I","-c","import time; time.sleep(300)")
                .withHostConfig(host).exec();
        String started=null;
        Capture capture=null;
        Long capabilityExit=null;
        boolean attachStarted=false;
        try {
            authority.run();docker.startContainerCmd(created.getId()).exec();
            var running=docker.inspectContainerCmd(created.getId()).exec();
            verifyCapabilityContainer(running,labels,work,expected,image,runtimePolicy);
            if(!Boolean.TRUE.equals(running.getState().getRunning()))throw unavailable("CAPABILITY_SANDBOX_NOT_RUNNING");
            started=startedAt(running);
            if(started==null)throw unavailable("CAPABILITY_SANDBOX_START_UNCONFIRMED");
            byte[] input=capabilityCheckInput(manifest,expected,command.operationId());
            if(input.length>4*1024*1024)throw unavailable("CAPABILITY_MATERIALIZATION_MANIFEST_TOO_LARGE");
            String script=capabilityCheckScript();
            var exec=docker.execCreateCmd(created.getId()).withAttachStdout(true).withAttachStderr(true).withAttachStdin(true)
                    .withTty(false).withPrivileged(false).withUser("10001:10001").withWorkingDir("/workspace")
                    .withCmd("python3","-I","-c",script,Integer.toString(input.length)).exec().getId();
            capture=new Capture();capture.execId=exec;authority.run();
            Capture stream=capture;
            Thread attach=new Thread(()->{
                try {docker.execStartCmd(exec).withDetach(false).withTty(false).withStdIn(new ByteArrayInputStream(input)).exec(stream);}
                catch(RuntimeException streamFailure) {stream.failure=streamFailure;stream.finished.countDown();}
                finally {stream.attachThreadFinished.countDown();}
            },"ap-capability-materialization-"+command.operationId());
            attach.setDaemon(true);attach.start();attachStarted=true;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(System.nanoTime()<deadline) {
                authority.run();var state=docker.inspectExecCmd(exec).exec();
                if(Boolean.FALSE.equals(state.isRunning())&&capture.finished.getCount()==0) {capabilityExit=state.getExitCodeLong();break;}
                Thread.sleep(50);
            }
            if(capabilityExit==null||capabilityExit!=0||capture.failure!=null||capture.truncated)
                throw unavailable(capabilityFailureCode(capabilityExit,capture.failure,capture.truncated,capture.stderr()));
            tools.jackson.databind.JsonNode result;
            try {result=JSON.readTree(capture.stdout());}
            catch(RuntimeException invalid){throw unavailable("CAPABILITY_MATERIALIZATION_RESULT_INVALID");}
            if(!manifest.hash().equals(result.path("manifestHash").asText())
                    ||result.path("verifiedFiles").asInt(-1)!=manifest.versions().stream().mapToInt(v->v.files().size()).sum()
                    ||!result.path("readOnlyMounts").asBoolean())throw unavailable("CAPABILITY_MATERIALIZATION_RESULT_MISMATCH");
            authority.run();
            InspectContainerResponse stopped=stopCapabilityContainer(created.getId(),capture,attachStarted);
            if(stopped==null)throw unavailable("CAPABILITY_MATERIALIZATION_STOP_UNCONFIRMED");
            String finished=stopped.getState().getFinishedAt();
            return new CapabilityMaterializationReceipt(command.commandId(),command.operationId(),command.workspaceId(),
                    command.generation(),command.fence(),"COMMITTED",manifest.hash(),runtimePolicy,
                    manifest.versions().stream().mapToInt(v->v.files().size()).sum(),true,0,stopped.getId(),image,
                    Instant.parse(started),Instant.parse(finished),null,1,true);
        } catch(InterruptedException|RuntimeException failure) {
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            InspectContainerResponse stopped=stopCapabilityContainer(created.getId(),capture,attachStarted);
            boolean stopConfirmed=stopped!=null;
            String actualStart=started;
            if(actualStart==null&&stopped!=null)actualStart=startedAt(stopped);
            String actualFinish=stopped==null?null:stopped.getState().getFinishedAt();
            String error=failure instanceof WorkspaceFailure workspace?workspace.code():
                    failure instanceof InterruptedException?"CAPABILITY_MATERIALIZATION_INTERRUPTED":"CAPABILITY_MATERIALIZATION_FAILED";
            var receipt=new CapabilityMaterializationReceipt(command.commandId(),command.operationId(),command.workspaceId(),
                    command.generation(),command.fence(),stopConfirmed?"FAILED":"BLOCKED",manifest.hash(),runtimePolicy,
                    0,false,safeExitCode(capabilityExit),created.getId(),image,
                    parseInstant(actualStart),parseInstant(actualFinish),error,1,stopConfirmed);
            throw new CapabilityMaterializationFailure(receipt,failure);
        }
    }

    private static Integer safeExitCode(Long exit) {
        return exit==null||exit<Integer.MIN_VALUE||exit>Integer.MAX_VALUE?null:exit.intValue();
    }

    /** Convert guest diagnostics to a small allow-listed code set; never persist guest stderr. */
    static String capabilityFailureCode(Long exit,Throwable streamFailure,boolean truncated,String stderr) {
        if(streamFailure!=null)return "CAPABILITY_EXEC_STREAM_FAILED";
        if(truncated)return "CAPABILITY_EXEC_OUTPUT_LIMIT";
        if(exit==null)return "CAPABILITY_EXEC_EXIT_UNCONFIRMED";
        if(exit==0)return "CAPABILITY_EXEC_RESULT_INVALID";
        String safe=stderr==null?"":stderr;
        if(safe.contains("FILE_IDENTITY"))return "CAPABILITY_FILE_IDENTITY_MISMATCH";
        if(safe.contains("FILE_HASH"))return "CAPABILITY_FILE_HASH_MISMATCH";
        if(safe.contains("INPUT_LENGTH"))return "CAPABILITY_INPUT_TRUNCATED";
        if(safe.contains("MOUNT_WRITABLE"))return "CAPABILITY_MOUNT_WRITABLE";
        if(safe.contains("PermissionError")||safe.contains("Permission denied"))return "CAPABILITY_PERMISSION_DENIED";
        if(safe.contains("FileNotFoundError"))return "CAPABILITY_INPUT_MISSING";
        if(safe.contains("EROFS"))return "CAPABILITY_READ_ONLY_CHECK_FAILED";
        return "CAPABILITY_GUEST_PROCESS_FAILED";
    }

    private InspectContainerResponse stopCapabilityContainer(String containerId,Capture capture,boolean attachStarted) {
        try {
            var current=docker.inspectContainerCmd(containerId).exec();
            if(isNeverStarted(current))return current;
            if(Boolean.TRUE.equals(current.getState().getRunning()))docker.stopContainerCmd(containerId).withTimeout(2).exec();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            do {
                var stopped=docker.inspectContainerCmd(containerId).exec();
                boolean callbackStopped=!attachStarted||capture!=null&&capture.finished.await(50,TimeUnit.MILLISECONDS)
                        &&capture.attachThreadFinished.await(50,TimeUnit.MILLISECONDS);
                if((confirmedContainerStopped(stopped.getState())||isNeverStarted(stopped))&&callbackStopped)return stopped;
                Thread.sleep(50);
            } while(System.nanoTime()<deadline);
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
        catch(RuntimeException unavailable) { /* An unknown stop result remains BLOCKED. */ }
        return null;
    }

    private boolean isNeverStarted(InspectContainerResponse container) {
        var state=container==null?null:container.getState();
        return state!=null&&"created".equals(state.getStatus())&&Boolean.FALSE.equals(state.getRunning())
                &&Long.valueOf(0).equals(state.getPidLong())&&startedAt(container)==null;
    }

    private static Instant parseInstant(String value) {
        if(value==null||value.isBlank())return null;
        try {Instant parsed=Instant.parse(value);return parsed.isAfter(Instant.EPOCH)?parsed:null;}
        catch(RuntimeException invalid){return null;}
    }

    private void verifyCapabilityContainer(InspectContainerResponse found,Map<String,String> labels,java.nio.file.Path work,
            Map<String,java.nio.file.Path> roots,String image,String policyHash) {
        var host=found.getHostConfig();var mounts=host.getBinds();
        if(!image.equals(found.getImageId())||found.getConfig().getLabels()==null||!found.getConfig().getLabels().entrySet().containsAll(labels.entrySet())
                ||!"10001:10001".equals(found.getConfig().getUser())||!Boolean.TRUE.equals(host.getReadonlyRootfs())||Boolean.TRUE.equals(host.getPrivileged())
                ||!"runsc".equals(host.getRuntime())||!"none".equals(host.getNetworkMode())||!Long.valueOf(SandboxPolicy.MEMORY).equals(host.getMemory())
                ||!Long.valueOf(SandboxPolicy.MEMORY).equals(host.getMemorySwap())||!Long.valueOf(1_000_000_000L).equals(host.getNanoCPUs())
                ||!Long.valueOf(128).equals(host.getPidsLimit())||host.getDevices()!=null&&host.getDevices().length!=0
                ||host.getCapAdd()!=null&&host.getCapAdd().length!=0||host.getCapDrop()==null||!Arrays.asList(host.getCapDrop()).equals(List.of(Capability.ALL))
                ||host.getSecurityOpts()==null||!host.getSecurityOpts().contains("no-new-privileges:true")||mounts==null||mounts.length!=roots.size()+1
                ||host.getTmpFs()==null||!"rw,noexec,nosuid,size=64m,uid=10001,gid=10001,mode=700".equals(host.getTmpFs().get("/tmp")))
            throw unavailable("CAPABILITY_SANDBOX_IDENTITY_MISMATCH");
        var byTarget=new HashMap<String,Bind>();for(var bind:mounts)byTarget.put(bind.getVolume().getPath(),bind);
        var workspace=byTarget.remove("/workspace");
        if(workspace==null||!work.toString().equals(workspace.getPath())||workspace.getAccessMode()!=AccessMode.rw)
            throw unavailable("CAPABILITY_SANDBOX_MOUNT_MISMATCH");
        for(var item:roots.entrySet()) {
            var bind=byTarget.remove(item.getKey());
            if(bind==null||!item.getValue().toString().equals(bind.getPath())||bind.getAccessMode()!=AccessMode.ro)
                throw unavailable("CAPABILITY_SANDBOX_MOUNT_MISMATCH");
        }
        if(!byTarget.isEmpty())throw unavailable("CAPABILITY_SANDBOX_MOUNT_MISMATCH");
    }

    private static byte[] capabilityCheckInput(CapabilityMaterialization.Manifest manifest,Map<String,java.nio.file.Path> roots,UUID operation) {
        var versions=new ArrayList<Map<String,Object>>();
        for(var version:manifest.versions())versions.add(Map.of("mount",version.mountTarget(),"writeProbe",".platform-ro-"+operation,
                "files",version.files().stream().map(file->Map.of("path",file.path(),"bytes",file.bytes(),"sha256",file.sha256())).toList()));
        return JSON.writeValueAsBytes(Map.of("manifestHash",manifest.hash(),"versions",versions));
    }

    private static String capabilityCheckScript() {
        return "import errno,hashlib,json,os,stat,sys\n"
                +"n=int(sys.argv[1]); raw=sys.stdin.buffer.read(n)\n"
                +"if len(raw)!=n: raise RuntimeError('INPUT_LENGTH')\n"
                +"m=json.loads(raw); total=0\n"
                +"for v in m['versions']:\n"
                +" r=os.open(v['mount'],os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW);\n"
                +" for f in v['files']:\n"
                +"  parts=f['path'].split('/'); d=os.dup(r)\n"
                +"  try:\n"
                +"   for p in parts[:-1]: n=os.open(p,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=d); os.close(d); d=n\n"
                +"   fd=os.open(parts[-1],os.O_RDONLY|os.O_NOFOLLOW,dir_fd=d); s=os.fstat(fd)\n"
                +"   if not stat.S_ISREG(s.st_mode) or s.st_size!=f['bytes']: raise RuntimeError('FILE_IDENTITY')\n"
                +"   h=hashlib.sha256(); size=0\n"
                +"   while True:\n"
                +"    b=os.read(fd,65536)\n"
                +"    if not b: break\n"
                +"    size+=len(b); h.update(b)\n"
                +"   os.close(fd)\n"
                +"   if size!=f['bytes'] or h.hexdigest()!=f['sha256']: raise RuntimeError('FILE_HASH')\n"
                +"   total+=1\n"
                +"  finally: os.close(d)\n"
                +" try: os.open(v['writeProbe'],os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=r); raise RuntimeError('MOUNT_WRITABLE')\n"
                +" except OSError as e:\n"
                +"  if e.errno!=errno.EROFS: raise\n"
                +" os.close(r)\n"
                +"print(json.dumps({'manifestHash':m['manifestHash'],'verifiedFiles':total,'readOnlyMounts':True},separators=(',',':')),flush=True)";
    }

    /** Remove only a previously persisted, exact stopped capability canary. */
    public void removeCapabilityMaterialization(CapabilityMaterializationCommand command,CapabilityMaterializationReceipt receipt) {
        if(receipt==null||!Set.of("COMMITTED","FAILED").contains(receipt.state())||!receipt.stopConfirmed()
                ||!command.operationId().equals(receipt.operationId()))throw unavailable("CAPABILITY_MATERIALIZATION_STOP_UNCONFIRMED");
        if(receipt.containerId()==null)return;
        InspectContainerResponse found;
        try {found=docker.inspectContainerCmd(receipt.containerId()).exec();}
        catch(NotFoundException alreadyRemoved){return;}
        var labels=found.getConfig().getLabels();
        boolean neverStarted=receipt.startedAt()==null&&receipt.finishedAt()==null&&isNeverStarted(found);
        boolean exited=confirmedContainerStopped(found.getState())&&receipt.finishedAt()!=null
                &&Objects.equals(receipt.finishedAt(),parseInstant(found.getState().getFinishedAt()));
        boolean startMatches=receipt.startedAt()==null?neverStarted:Objects.equals(receipt.startedAt(),parseInstant(startedAt(found)));
        if((!neverStarted&&!exited)||!startMatches||!policy.image().equals(receipt.imageId())||!policy.image().equals(found.getImageId())
                ||labels==null||!command.operationId().toString().equals(labels.get("ap.operation"))
                ||!command.commandId().toString().equals(labels.get("ap.command"))
                ||!command.taskId().toString().equals(labels.get("ap.task"))
                ||!command.spaceId().toString().equals(labels.get("ap.space"))
                ||!command.workspaceId().toString().equals(labels.get("ap.workspace"))
                ||!Long.toString(command.generation()).equals(labels.get("ap.generation"))
                ||!Long.toString(command.fence()).equals(labels.get("ap.fence"))
                ||!command.manifestHash().equals(labels.get("ap.manifest"))
                ||!Objects.equals(receipt.policyHash(),labels.get("ap.policy")))
            throw unavailable("CAPABILITY_MATERIALIZATION_STOP_MISMATCH");
        docker.removeContainerCmd(found.getId()).withForce(false).withRemoveVolumes(false).exec();
    }

    @Override public void close() throws java.io.IOException { docker.close(); }

    private static final class Capture extends ResultCallback.Adapter<Frame> {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        private final CountDownLatch finished = new CountDownLatch(1);
        private final CountDownLatch attachThreadFinished = new CountDownLatch(1);
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
        synchronized String stderr() { return err.toString(StandardCharsets.UTF_8); }
        synchronized Output output(long exit, boolean timeout, boolean cancelled, Stop stopped) {
            return new Output(execId, exit, stdout(), err.toString(StandardCharsets.UTF_8), truncated, timeout, cancelled, stopped);
        }
    }
}
