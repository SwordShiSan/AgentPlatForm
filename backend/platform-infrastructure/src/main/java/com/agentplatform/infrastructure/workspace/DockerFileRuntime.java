package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.files.*;
import com.agentplatform.domain.files.FileData.Command;
import com.agentplatform.domain.workspace.RunnerInstance;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import tools.jackson.databind.json.JsonMapper;

/** Fixed trusted streaming filesystem adapter. It never creates a guest environment. */
public final class DockerFileRuntime implements FileRuntime {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final FileRuntimePolicy policy;
    private final AuxiliaryContainerSlot slots;
    private final UUID instanceId;
    private final DockerClient docker;
    interface Active { boolean quiescent(); }
    private final ConcurrentMap<UUID,Active> active=new ConcurrentHashMap<>();
    public DockerFileRuntime(FileRuntimePolicy policy,AuxiliaryContainerSlot slots,UUID instanceId) {
        this.policy=policy;this.slots=slots;this.instanceId=instanceId;
        var config=DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost("unix:///var/run/docker.sock").withApiVersion("1.45").build();
        var transport=new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost()).maxConnections(4)
                .connectionTimeout(Duration.ofSeconds(2)).responseTimeout(Duration.ofSeconds(130)).build();
        docker=DockerClientImpl.getInstance(config,transport);
    }
    @Override public String policyHash(){return policy.hash();}
    @Override public RunnerInstance runnerInstance() {
        String name=System.getenv("HOSTNAME");
        if(name==null||!name.matches("[a-f0-9]{12,64}"))throw new FileFailure(503,"RUNNER_INSTANCE_UNVERIFIED");
        var self=docker.inspectContainerCmd(name).exec();
        if(!Boolean.TRUE.equals(self.getState().getRunning())||self.getConfig().getLabels()==null
                ||!"runner".equals(self.getConfig().getLabels().get("ap.role")))throw new FileFailure(503,"RUNNER_INSTANCE_UNVERIFIED");
        return new RunnerInstance(instanceId,self.getId(),self.getImageId(),self.getState().getStartedAt());
    }
    private static String name(Command command){return "ap-file-stream-"+command.operation().commandId();}
    private Map<String,String> labels(Command command) {
        var op=command.operation();
        return Map.of("ap.role","trusted-file-stream","ap.command",op.commandId().toString(),"ap.operation",op.id().toString(),
                "ap.space",op.spaceId().toString(),"ap.workspace",op.workspaceId().toString(),"ap.generation",Long.toString(op.generation()),
                "ap.fence",Long.toString(op.fence()),"ap.policy",policy.hash(),"ap.runnerInstance",instanceId.toString());
    }
    private InspectContainerResponse exact(Command command,Physical physical) {
        if(physical==null||physical.containerId()==null)throw new FileFailure(409,"FILE_PHYSICAL_IDENTITY_UNCONFIRMED");
        var row=docker.inspectContainerCmd(physical.containerId()).exec();var host=row.getHostConfig();
        var expected=labels(command);var actual=row.getConfig();
        // A reconciler can observe another registered runner, but all command/policy labels must remain exact.
        var stable=new HashMap<>(expected);stable.remove("ap.runnerInstance");
        if(!physical.containerId().equals(row.getId())||!policy.image().equals(row.getImageId())||actual.getLabels()==null
                ||!actual.getLabels().entrySet().containsAll(stable.entrySet())||!"10001:10001".equals(actual.getUser())
                ||!"runc".equals(host.getRuntime())||!"/workspace".equals(actual.getWorkingDir())
                ||!Arrays.equals(new String[]{"python3","/opt/platform/file-helper-entrypoint.py","idle"},actual.getEntrypoint())
                ||!"none".equals(host.getNetworkMode())||!Boolean.TRUE.equals(host.getReadonlyRootfs())
                ||!Long.valueOf(64L*1024*1024).equals(host.getMemory())||!Long.valueOf(64L*1024*1024).equals(host.getMemorySwap())
                ||!Long.valueOf(32).equals(host.getPidsLimit())||!Long.valueOf(1_000_000_000L).equals(host.getNanoCPUs())
                ||Boolean.TRUE.equals(host.getPrivileged())||host.getCapAdd()!=null&&host.getCapAdd().length>0
                ||host.getCapDrop()==null||!Arrays.asList(host.getCapDrop()).contains(Capability.ALL)
                ||host.getDevices()!=null&&host.getDevices().length>0||host.getBinds()!=null&&host.getBinds().length>0
                ||host.getMounts()==null||host.getMounts().size()!=1||host.getMounts().getFirst().getType()!=MountType.BIND
                ||!policy.work(command).equals(host.getMounts().getFirst().getSource())||!"/workspace".equals(host.getMounts().getFirst().getTarget())
                ||Boolean.TRUE.equals(host.getMounts().getFirst().getReadOnly())
                ||host.getSecurityOpts()==null||!host.getSecurityOpts().contains("no-new-privileges:true"))
            throw new FileFailure(503,"FILE_HELPER_IDENTITY_MISMATCH");
        if(physical.startedAt()!=null&&!physical.startedAt().equals(row.getState().getStartedAt()))throw new FileFailure(503,"FILE_HELPER_IDENTITY_MISMATCH");
        return row;
    }
    private void verifyStorage(Command command,AtomicBoolean stopped) {
        String verifier="ap-file-verify-"+command.operation().commandId();
        try{docker.inspectContainerCmd(verifier).exec();throw new FileFailure(409,"FILE_VERIFIER_RECONCILIATION_REQUIRED");}
        catch(NotFoundException absent){/* A new admitted operation has exactly one verifier invocation. */}
        var host=HostConfig.newHostConfig().withNetworkMode("none").withReadonlyRootfs(true)
                .withMemory(64L*1024*1024).withMemorySwap(64L*1024*1024).withPidsLimit(32L)
                .withCapDrop(Capability.ALL).withCapAdd(Capability.SYS_ADMIN,Capability.CHOWN,Capability.SETUID,Capability.SETGID)
                .withSecurityOpts(List.of("no-new-privileges:true"))
                .withDevices(DockerWorkspaceAllocator.fixedQuotaDevice(policy.quotaDevice()))
                .withMounts(List.of(new Mount().withType(MountType.BIND).withSource(policy.workspaceRoot()).withTarget("/quota").withReadOnly(true)))
                .withTmpFs(Map.of("/tmp","rw,noexec,nosuid,size=8m"));
        var created=docker.createContainerCmd(policy.quotaImage()).withName(verifier).withUser("0:0")
                .withLabels(Map.of("ap.role","file-storage-verifier","ap.command",command.operation().commandId().toString(),"ap.policy",policy.hash()))
                .withHostConfig(host).withEntrypoint("python3","/opt/workspace-quota-verifier.py")
                .withCmd("--space",command.operation().spaceId().toString(),"--workspace",command.operation().workspaceId().toString(),
                        "--project",Integer.toString(command.projectId()),"--mount-uuid",policy.mountUuid().toString()).exec();
        stopped.set(false);
        docker.startContainerCmd(created.getId()).exec();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(System.nanoTime()<until) {
            var row=docker.inspectContainerCmd(created.getId()).exec();
            if(DockerSandboxRuntime.confirmedContainerStopped(row.getState())) {
                stopped.set(true);
                try(var capture=new Capture()) {
                    docker.logContainerCmd(created.getId()).withStdOut(true).withStdErr(true).withTail(1).exec(capture);
                    if(!capture.done.await(5,TimeUnit.SECONDS)||capture.failure!=null||capture.truncated||!Long.valueOf(0).equals(row.getState().getExitCodeLong())
                            ||!JSON.readTree(capture.stdout()).path("verified").asBoolean())throw new FileFailure(503,"WORKSPACE_VERIFICATION_UNAVAILABLE");
                } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new FileFailure(503,"WORKSPACE_VERIFICATION_UNAVAILABLE");}
                docker.removeContainerCmd(created.getId()).withForce(false).exec();return;
            }
            try{Thread.sleep(100);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}
        }
        throw new FileFailure(503,"WORKSPACE_VERIFICATION_UNAVAILABLE");
    }
    private com.github.dockerjava.api.command.CreateContainerResponse createHelper(Command command,Authority authority) {
        var host=HostConfig.newHostConfig().withRuntime("runc").withNetworkMode("none").withReadonlyRootfs(true)
                .withMemory(64L*1024*1024).withMemorySwap(64L*1024*1024).withNanoCPUs(1_000_000_000L).withPidsLimit(32L)
                .withCapDrop(Capability.ALL).withSecurityOpts(List.of("no-new-privileges:true"))
                .withMounts(List.of(new Mount().withType(MountType.BIND).withSource(policy.work(command)).withTarget("/workspace").withReadOnly(false)))
                .withTmpFs(Map.of("/tmp","rw,noexec,nosuid,size=8m,uid=10001,gid=10001,mode=700"))
                .withLogConfig(new LogConfig(LogConfig.LoggingType.JSON_FILE,Map.of("max-size","10m","max-file","3")));
        var created=docker.createContainerCmd(policy.image()).withName(name(command)).withUser("10001:10001").withWorkingDir("/workspace")
                .withLabels(labels(command)).withHostConfig(host).withEntrypoint("python3","/opt/platform/file-helper-entrypoint.py","idle").exec();
        authority.created(created.getId());authority.startRequested();authority.check();
        docker.startContainerCmd(created.getId()).exec();
        var row=docker.inspectContainerCmd(created.getId()).exec();
        if(!Boolean.TRUE.equals(row.getState().getRunning()))throw new FileFailure(503,"FILE_HELPER_START_UNCONFIRMED");
        authority.started(row.getState().getStartedAt());authority.check();exact(command,authority.physical());
        return created;
    }
    @Override public Result apply(Command command,InputStream content,Authority authority) {
        boolean scan="SCAN".equals(command.operation().kind());
        if(!scan&&(command.upload()==null||!"APPLY_UPLOAD".equals(command.operation().kind()))||!policy.image().equals(command.operation().helperImageId())
                ||!policy.hash().equals(command.operation().helperPolicyHash()))throw new FileFailure(409,"FILE_POLICY_MISMATCH");
        var permit=slots.tryAcquire();if(permit==null)throw new FileFailure(423,"WORKSPACE_HELPER_BUSY");
        var verifierStopped=new AtomicBoolean(true);boolean release=false;
        Capture capture=new Capture();TrackedInput input=null;boolean execInvoked=false;boolean createInvoked=false;
        try {
            authority.check();verifyStorage(command,verifierStopped);authority.check();
            try{createInvoked=true;docker.inspectContainerCmd(name(command)).exec();throw new FileFailure(409,"FILE_HELPER_RECONCILIATION_REQUIRED");}
            catch(NotFoundException absent){createInvoked=false;/* Never recreate an existing command. */}
            createInvoked=true;
            var created=createHelper(command,authority);
            var header=new LinkedHashMap<String,Object>();header.put("protocol",scan?"file-scan-v1":"file-stream-v2");header.put("operationId",command.operation().id());
            header.put("filesystemDevice",List.of(7,Integer.parseInt(policy.quotaDevice().substring("/dev/loop".length()))));
            if(scan) {
                header.put("parent",command.operation().scanParent());header.put("cursor",command.operation().scanCursor());
                header.put("epoch",command.operation().scanEpoch());header.put("stamp",command.operation().scanStamp());
            } else {
                header.put("path",command.upload().relativePath());header.put("bytes",command.upload().sizeBytes());header.put("sha256",command.upload().sha256());header.put("expectedSha256",command.baseSha256());
            }
            byte[] metadata=(JSON.writeValueAsString(header)+"\n").getBytes(StandardCharsets.UTF_8);
            String exec=docker.execCreateCmd(created.getId()).withAttachStdin(true).withAttachStdout(true).withAttachStderr(true).withTty(false)
                    .withCmd("python3",scan?"/opt/platform/file-scan-bridge.py":"/opt/platform/file-stream-bridge.py").exec().getId();
            authority.exec(exec);authority.check();
            InputStream payload=scan?content:new SequenceInputStream(content,
                    new ByteArrayInputStream(HexFormat.of().parseHex(command.upload().sha256())));
            input=new TrackedInput(new SequenceInputStream(new ByteArrayInputStream(metadata),payload));capture.input=input;active.put(command.operation().commandId(),capture);
            execInvoked=true;
            docker.execStartCmd(exec).withDetach(false).withTty(false).withStdIn(input).exec(capture);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
            while(capture.done.getCount()!=0&&System.nanoTime()<until){authority.check();capture.done.await(100,TimeUnit.MILLISECONDS);}
            input.close();capture.close();
            boolean callbacksStopped=input.stopped()&&capture.done.getCount()==0;
            Long exitCode=null;
            try{var execution=docker.inspectExecCmd(exec).exec();if(Boolean.FALSE.equals(execution.isRunning()))exitCode=execution.getExitCodeLong();}
            catch(NotFoundException retired){ /* A retired exec cannot prove successful completion. */ }
            Stop stop=stop(command,authority.physical(),callbacksStopped);
            if(stop==null)throw new FileFailure(409,"FILE_STOP_UNCONFIRMED");
            release=true;
            String result=capture.stdout();String state="UNKNOWN";
            if(capture.failure==null&&!capture.truncated&&callbacksStopped) {
                var value=JSON.readTree(result);
                if(Long.valueOf(0).equals(exitCode)&&"APPLIED".equals(value.path("state").asString())&&!scan)state="APPLIED";
                else if(Long.valueOf(0).equals(exitCode)&&"SCANNED".equals(value.path("state").asString())&&scan)state="SCANNED";
                else if(exitCode!=null&&exitCode!=0&&Set.of("FILE_NAME_CONFLICT","FILE_VERSION_CONFLICT","FILE_PATH_REJECTED","FILE_KIND_REJECTED","FILE_NAME_NORMALIZATION_CONFLICT","FILE_CONTENT_MISMATCH","FILE_STORAGE_FULL","FILE_MOUNT_REJECTED","FILE_ATOMIC_CREATE_UNAVAILABLE").contains(value.path("code").asString()))state="NOT_APPLIED";
            }
            if("UNKNOWN".equals(state))result=JSON.writeValueAsString(Map.of("state","UNKNOWN","code","FILE_RESULT_UNKNOWN"));
            return new Result(state,result,JSON.writeValueAsString(stop));
        } catch(RuntimeException failure) {
            if(!createInvoked&&verifierStopped.get()) {
                release=true;
                var stop=new Stop(command.operation().commandId(),null,policy.image(),null,null,null,true,true,true,Instant.now());
                String code=failure instanceof FileFailure?failure.getMessage():"FILE_PREPARATION_FAILED";
                return new Result("NOT_APPLIED",JSON.writeValueAsString(Map.of("state","FAILED","code",code)),JSON.writeValueAsString(stop));
            }
            throw failure;
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new FileFailure(409,"FILE_RESULT_UNKNOWN");
        } catch(IOException failure) {throw new FileFailure(409,"FILE_RESULT_UNKNOWN");}
        finally {
            try {
                if(input!=null)input.close();capture.close();
                if(!release&&verifierStopped.get())release=stop(command,authority.physical(),!execInvoked||capture.quiescent())!=null;
            } catch(Exception uncertain){/* Retain capacity; a cancelled Future is not stop evidence. */}
            if(!execInvoked||capture.quiescent())active.remove(command.operation().commandId(),capture);
            if(release&&verifierStopped.get())slots.release(permit);else slots.retain(permit,verifierStopped.get()?command.operation().commandId():null);
        }
    }
    private Stop stop(Command command,Physical physical,boolean callbacksStopped) {
        if(!callbacksStopped||physical==null||physical.containerId()==null)return null;
        var row=exact(command,physical);
        if(physical.startedAt()==null) {
            if(Boolean.TRUE.equals(row.getState().getRunning()))docker.stopContainerCmd(row.getId()).withTimeout(5).exec();
            return null; // Stop best effort, but do not invent the missing persisted start identity.
        }
        if(Boolean.TRUE.equals(row.getState().getRunning()))docker.stopContainerCmd(row.getId()).withTimeout(5).exec();
        row=exact(command,physical);
        if(!DockerSandboxRuntime.confirmedContainerStopped(row.getState()))return null;
        if(physical.execId()!=null)try{if(!Boolean.FALSE.equals(docker.inspectExecCmd(physical.execId()).exec().isRunning()))return null;}
        catch(NotFoundException retired){if(!DockerSandboxRuntime.confirmedContainerStopped(row.getState()))return null;}
        return new Stop(command.operation().commandId(),row.getId(),row.getImageId(),physical.startedAt(),physical.execId(),
                row.getState().getFinishedAt(),false,true,true,Instant.now());
    }
    /** Same fixed helper and canonical file command; no guest program or object address enters stdin. */
    @Override public Result capture(Command command,CaptureTarget target,CaptureSink sink,Authority authority) {
        if(!"CAPTURE".equals(command.operation().kind())||command.upload()!=null||!policy.image().equals(command.operation().helperImageId())
                ||!policy.hash().equals(command.operation().helperPolicyHash()))throw new FileFailure(409,"FILE_POLICY_MISMATCH");
        var permit=slots.tryAcquire();if(permit==null)throw new FileFailure(423,"WORKSPACE_HELPER_BUSY");
        var verifierStopped=new AtomicBoolean(true);boolean release=false;
        FileCaptureChannel capture=null;TrackedInput input=null;PipedOutputStream send=null;
        boolean createInvoked=false,transferLaunched=false,originalAbsent=false;
        var stopped=new AtomicReference<Stop>();var failure=new AtomicReference<Throwable>();var descriptor=new AtomicReference<CapturedFileStream.Descriptor>();
        var ioExited=new CountDownLatch(1);
        try {
            authority.check();verifyStorage(command,verifierStopped);authority.check();
            try{createInvoked=true;docker.inspectContainerCmd(name(command)).exec();throw new FileFailure(409,"FILE_HELPER_RECONCILIATION_REQUIRED");}
            catch(NotFoundException absent){createInvoked=false;originalAbsent=true;/* One accepted command creates one exact helper. */}
            createInvoked=true;var created=createHelper(command,authority);
            var source=new PipedInputStream(8192);send=new PipedOutputStream(source);input=new TrackedInput(source);
            capture=new FileCaptureChannel(input::stopped,ioExited);active.put(command.operation().commandId(),capture);
            var header=Map.of("protocol","file-capture-v1","operationId",command.operation().id(),"path",target.path(),"maxBytes",target.maxBytes(),
                    "filesystemDevice",List.of(7,Integer.parseInt(policy.quotaDevice().substring("/dev/loop".length()))));
            send.write((JSON.writeValueAsString(header)+"\n").getBytes(StandardCharsets.UTF_8));send.flush();
            String exec=docker.execCreateCmd(created.getId()).withAttachStdin(true).withAttachStdout(true).withAttachStderr(true).withTty(false)
                    .withCmd("python3","/opt/platform/file-capture-bridge.py").exec().getId();
            authority.exec(exec);authority.check();
            docker.execStartCmd(exec).withDetach(false).withTty(false).withStdIn(input).exec(capture);
            final var channel=capture;final var stdin=input;final var ready=send;
            transferLaunched=true;
            Thread.ofVirtual().name("file-capture-"+command.operation().commandId()).start(()->{
                try {
                    var value=CapturedFileStream.descriptor(channel.source,command.operation().id(),target.maxBytes());descriptor.set(value);
                    authority.check();sink.described(value.bytes(),value.sha256());authority.check();
                    ready.write(CapturedFileStream.ready(value));ready.close();
                    try(var content=new CapturedFileStream(channel.source,value,authority::check,()->{
                        try {
                            stdin.close();
                            if(!channel.done.await(5,TimeUnit.SECONDS)||channel.failure!=null||!stdin.stopped())throw new FileFailure(409,"FILE_STOP_UNCONFIRMED");
                            var execution=docker.inspectExecCmd(exec).exec();
                            if(!Boolean.FALSE.equals(execution.isRunning())||!Long.valueOf(0).equals(execution.getExitCodeLong()))throw new FileFailure(409,"FILE_CAPTURE_FAILED");
                            var fact=stop(command,authority.physical(),true);
                            if(fact==null)throw new FileFailure(409,"FILE_STOP_UNCONFIRMED");
                            stopped.set(fact);sink.stopped(fact);
                        }catch(IOException problem){throw new FileFailure(409,"FILE_STOP_UNCONFIRMED");}
                        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new FileFailure(409,"FILE_STOP_UNCONFIRMED");}
                    })) {
                        var object=sink.store(value.bytes(),value.sha256(),content);
                        if(!content.verified()||object==null||object.bytes()!=value.bytes()||!value.sha256().equals(object.sha256()))
                            throw new FileFailure(503,"ASSET_IDENTITY_MISMATCH");
                    }
                }catch(Throwable problem){failure.set(problem);}
                finally{try{ready.close();stdin.close();}catch(IOException problem){failure.compareAndSet(null,problem);}ioExited.countDown();}
            });
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
            while(ioExited.getCount()!=0&&System.nanoTime()<until&&Instant.now().isBefore(command.operation().deadlineAt())) {
                authority.check();if(capture.failure!=null)throw new FileFailure(409,"FILE_CAPTURE_UNCONFIRMED");ioExited.await(100,TimeUnit.MILLISECONDS);
            }
            if(ioExited.getCount()!=0)throw new FileFailure(409,"FILE_CAPTURE_TIMEOUT");
            if(failure.get()!=null||stopped.get()==null||!capture.quiescent())throw new FileFailure(409,"FILE_CAPTURE_UNCONFIRMED");
            release=true;var value=descriptor.get();
            return new Result("CAPTURED",JSON.writeValueAsString(Map.of("protocol","file-capture-v1","operationId",command.operation().id(),
                    "state","CAPTURED","bytes",value.bytes(),"sha256",value.sha256())),JSON.writeValueAsString(stopped.get()));
        }catch(RuntimeException problem){
            if(!createInvoked&&originalAbsent&&verifierStopped.get()) {
                var fact=new Stop(command.operation().commandId(),null,policy.image(),null,null,null,true,true,true,Instant.now());
                stopped.set(fact);sink.stopped(fact);release=true;
                return new Result("NOT_APPLIED",JSON.writeValueAsString(Map.of("state","NOT_APPLIED","code","FILE_CAPTURE_NOT_STARTED")),JSON.writeValueAsString(fact));
            }
            throw problem;
        }catch(IOException problem){throw new FileFailure(409,"FILE_CAPTURE_UNCONFIRMED");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new FileFailure(409,"FILE_CAPTURE_UNCONFIRMED");}
        finally {
            if(!transferLaunched)ioExited.countDown();
            try {
                if(send!=null)send.close();if(input!=null)input.close();if(capture!=null)capture.close();
                // Stop the exact helper even while a transport is unwinding. Only
                // actual IO/callback completion plus a persisted receipt releases capacity.
                var physical=authority.physical();
                if(verifierStopped.get()&&physical!=null&&physical.startedAt()!=null) {
                    var row=exact(command,physical);if(Boolean.TRUE.equals(row.getState().getRunning()))docker.stopContainerCmd(row.getId()).withTimeout(5).exec();
                    if(capture!=null&&capture.quiescent()) {
                        var fact=stopped.get()==null?stop(command,physical,true):stopped.get();
                        if(fact!=null){sink.stopped(fact);release=true;}
                    }
                }
            }catch(Exception uncertain){release=false;}
            if(capture!=null&&capture.quiescent())active.remove(command.operation().commandId(),capture);
            if(release&&verifierStopped.get())slots.release(permit);else slots.retain(permit,verifierStopped.get()?command.operation().commandId():null);
        }
    }
    @Override public Result reconcile(Command command,Authority authority,boolean priorRunnerStopped) {
        var execution=active.get(command.operation().commandId());
        if(execution!=null){if(!execution.quiescent())return null;active.remove(command.operation().commandId(),execution);}
        var retained=slots.tryReconcile(command.operation().commandId());var permit=retained==null?slots.tryAcquire():retained;
        if(permit==null)return null;
        boolean release=false;
        try {
            // Caller has independently authorized an original-instance/prior-proof stop observation.
            var stopped=stop(command,authority.physical(),true);
            if(stopped==null)return null;
            release=true;
            return new Result("UNKNOWN",JSON.writeValueAsString(Map.of("state","UNKNOWN","code","FILE_RESULT_UNKNOWN")),JSON.writeValueAsString(stopped));
        } finally {if(release)slots.release(permit);else slots.retain(permit,command.operation().commandId());}
    }
    @Override public void close(){try{docker.close();}catch(IOException failure){throw new IllegalStateException(failure);}}
    @Override public void retire(Command command,Stop receipt) {
        if(receipt==null||!command.operation().commandId().equals(receipt.commandId())||!receipt.execStopped()||!receipt.callbacksStopped())
            throw new FileFailure(409,"FILE_STOP_UNCONFIRMED");
        if(receipt.neverStarted()&&receipt.containerId()==null)return;
        try {
            var row=exact(command,new Physical(receipt.containerId(),receipt.startedAt(),receipt.execId()));
            if(!DockerSandboxRuntime.confirmedContainerStopped(row.getState())||!Objects.equals(receipt.finishedAt(),row.getState().getFinishedAt()))
                throw new FileFailure(409,"FILE_STOP_UNCONFIRMED");
            docker.removeContainerCmd(row.getId()).withForce(false).exec();
        }catch(NotFoundException retired){/* The caller already holds the immutable exact stop receipt. */}
    }
    private static final class TrackedInput extends FilterInputStream {
        private final AtomicInteger readers=new AtomicInteger();private volatile boolean closed,closeCompleted,closeFailed;
        TrackedInput(InputStream in){super(in);}
        @Override public int read() throws IOException {byte[] value=new byte[1];return read(value,0,1)==-1?-1:value[0]&255;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException{
            readers.incrementAndGet();try{if(closed)throw new IOException("File stream closed");return in.read(bytes,offset,length);}finally{readers.decrementAndGet();}
        }
        @Override public void close()throws IOException{
            closed=true;try{super.close();if(!closeFailed)closeCompleted=true;}catch(IOException|RuntimeException failed){closeFailed=true;throw failed;}
        }
        boolean stopped(){return closed&&closeCompleted&&!closeFailed&&readers.get()==0;}
    }
    private static final class Capture extends ResultCallback.Adapter<Frame> implements Active {
        final CountDownLatch done=new CountDownLatch(1);final ByteArrayOutputStream bytes=new ByteArrayOutputStream();volatile Throwable failure;volatile boolean truncated;volatile TrackedInput input;
        @Override public boolean quiescent(){return done.getCount()==0&&(input==null||input.stopped());}
        @Override public synchronized void onNext(Frame frame){if(frame.getPayload().length>32768-bytes.size()){truncated=true;return;}bytes.writeBytes(frame.getPayload());}
        @Override public void onError(Throwable failure){this.failure=failure;done.countDown();}
        @Override public void onComplete(){done.countDown();}
        synchronized String stdout(){return bytes.toString(StandardCharsets.UTF_8);}
        @Override public void close(){try{super.close();}catch(IOException problem){failure=problem;}}
    }
}
