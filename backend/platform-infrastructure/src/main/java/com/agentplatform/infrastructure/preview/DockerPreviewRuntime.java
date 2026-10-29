package com.agentplatform.infrastructure.preview;

import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.domain.preview.PreviewData;
import com.agentplatform.domain.preview.PreviewData.Command;
import com.agentplatform.domain.preview.PreviewRuntime;
import com.agentplatform.domain.workspace.RunnerInstance;
import com.agentplatform.infrastructure.workspace.SandboxPolicy;
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
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

/** Fixed no-network renderer; no user program, URL, mount or Docker option can reach this adapter. */
public final class DockerPreviewRuntime implements PreviewRuntime {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Map<String,String> FORMATS=Map.ofEntries(Map.entry("PDF","pdf"),Map.entry("DOCX","docx"),Map.entry("XLSX","xlsx"),Map.entry("PPTX","pptx"),
            Map.entry("PNG","png"),Map.entry("JPEG","jpeg"),Map.entry("GIF","gif"),Map.entry("WEBP","webp"),Map.entry("HTML","html"),Map.entry("SVG","svg"),Map.entry("TEXT","txt"));
    private static final String TMPFS="rw,noexec,nosuid,size=256m,uid=10001,gid=10001,mode=700";
    private static final String[] ENTRY={"/usr/bin/tini","--","java","-Xms16m","-Xmx192m","-XX:MaxDirectMemorySize=16m","-Djava.awt.headless=true","-Djava.io.tmpdir=/tmp/preview","-cp","/opt/platform/renderer.jar:/opt/platform/lib/*","com.agentplatform.renderer.PreviewRendererMain"};
    private final PreviewRuntimePolicy policy;
    private final UUID instance;
    private final DockerClient docker;
    private final Map<UUID,ReadCapture> activeReads=new ConcurrentHashMap<>();
    private final Map<UUID,List<String>> reads=new ConcurrentHashMap<>();
    public DockerPreviewRuntime(PreviewRuntimePolicy policy,UUID instance) {
        this.policy=policy;this.instance=Objects.requireNonNull(instance);
        var config=DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost("unix:///var/run/docker.sock").withApiVersion("1.45").build();
        docker= DockerClientImpl.getInstance(config,new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost()).maxConnections(4)
                .connectionTimeout(Duration.ofSeconds(2)).responseTimeout(Duration.ofSeconds(15)).build());
    }
    DockerPreviewRuntime(PreviewRuntimePolicy policy,UUID instance,DockerClient docker){this.policy=policy;this.instance=instance;this.docker=docker;}
    @Override public String policyHash(){return policy.hash();}
    @Override public RunnerInstance runnerInstance() {
        String host=System.getenv("HOSTNAME");if(host==null||!host.matches("[0-9a-f]{12,64}"))throw failure("RUNNER_INSTANCE_UNVERIFIED");
        var self=docker.inspectContainerCmd(host).exec();
        if(!Boolean.TRUE.equals(self.getState().getRunning())||self.getConfig().getLabels()==null||!"runner".equals(self.getConfig().getLabels().get("ap.role")))throw failure("RUNNER_INSTANCE_UNVERIFIED");
        return new RunnerInstance(instance,self.getId(),self.getImageId(),self.getState().getStartedAt());
    }
    private static FileFailure failure(String code){return new FileFailure(503,code);}
    private static String name(Command c){return "ap-preview-"+c.commandId();}
    private Path jobRoot(Command c){return policy.stagingRoot().resolve(c.commandId().toString());}
    private Map<String,String> labels(Command c) {
        return Map.of("ap.role","preview-renderer","ap.preview",c.previewId().toString(),"ap.command",c.commandId().toString(),"ap.space",c.spaceId().toString(),
                "ap.version",c.versionId().toString(),"ap.generation",Long.toString(c.generation()),"ap.fence",Long.toString(c.fence()),"ap.policy",policy.hash());
    }
    private String extension(Command c){return FORMATS.get(c.sourceFormat());}
    private String[] args(Command c){return new String[]{extension(c),c.requestedFormat(),c.sourceSha256()};}
    private void command(Command c) {
        if(c==null||!FORMATS.containsKey(c.sourceFormat())||!Set.of("AUTO","PDF","IMAGE","HTML_STATIC","TEXT").contains(c.requestedFormat())
                ||!policy.image().equals(c.imageId())||!policy.bundle().equals(c.bundle())||!policy.hash().equals(c.policyHash())||c.sourceBytes()<0||c.sourceBytes()>PreviewData.MAX_BYTES
                ||c.sourceSha256()==null||!c.sourceSha256().matches("[a-f0-9]{64}")||c.generation()<1||c.fence()<1)throw failure("PREVIEW_POLICY_MISMATCH");
    }
    private void runtime() {
        try {
            var info=docker.infoCmd().exec().getRuntimes().get("runsc");
            if(info==null||!SandboxPolicy.RUNSC_PATH.equals(info.getPath())||Files.isSymbolicLink(policy.runtimeAttestation()))throw failure("PREVIEW_RUNTIME_UNAVAILABLE");
            var value=JSON.readTree(Files.readString(policy.runtimeAttestation()));long verified=value.path("verifiedAt").asLong();
            if(!SandboxPolicy.RUNSC_PATH.equals(value.path("path").asText())||!SandboxPolicy.RUNSC_SHA256.equals(value.path("sha256").asText())
                    ||!value.path("runtimeArgs").isArray()||value.path("runtimeArgs").size()!=1||!"--platform=systrap".equals(value.path("runtimeArgs").get(0).asText())
                    ||verified>Instant.now().getEpochSecond()+5||verified<Instant.now().minusSeconds(86400).getEpochSecond())throw failure("PREVIEW_RUNTIME_UNAVAILABLE");
        }catch(IOException|NullPointerException invalid){throw failure("PREVIEW_RUNTIME_UNAVAILABLE");}
    }
    private void paths(Command c,Path source,Path output)throws IOException {
        Path root=jobRoot(c);
        if(!source.toAbsolutePath().normalize().equals(root.resolve("input"))||!output.toAbsolutePath().normalize().equals(root.resolve("output")))throw failure("PREVIEW_STAGING_REJECTED");
        for(Path path=root;path!=null&&path.startsWith(policy.stagingRoot());path=path.getParent())if(Files.isSymbolicLink(path))throw failure("PREVIEW_STAGING_REJECTED");
        if(Files.isSymbolicLink(source)||!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)||Files.size(source)!=c.sourceBytes()||!hash(source).equals(c.sourceSha256()))throw failure("PREVIEW_INPUT_IDENTITY_MISMATCH");
        if(!Files.exists(output,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(output);
        if(Files.isSymbolicLink(output)||!Files.isDirectory(output,LinkOption.NOFOLLOW_LINKS))throw failure("PREVIEW_STAGING_REJECTED");
    }
    private InspectContainerResponse exact(Command c,String id,String startedAt) {
        var row=docker.inspectContainerCmd(id).exec();var h=row.getHostConfig();var config=row.getConfig();var mounts=h.getMounts();
        if(!name(c).equals(row.getName().replaceFirst("^/",""))||!policy.image().equals(row.getImageId())||config.getLabels()==null||!config.getLabels().entrySet().containsAll(labels(c).entrySet())
                ||!"10001:10001".equals(config.getUser())||!Arrays.equals(ENTRY,config.getEntrypoint())||!Arrays.equals(args(c),config.getCmd())
                ||!"runsc".equals(h.getRuntime())||!"none".equals(h.getNetworkMode())||!Boolean.TRUE.equals(h.getReadonlyRootfs())||Boolean.TRUE.equals(h.getPrivileged())
                ||!Long.valueOf(PreviewRuntimePolicy.MEMORY).equals(h.getMemory())||!Long.valueOf(PreviewRuntimePolicy.MEMORY).equals(h.getMemorySwap())
                ||!Long.valueOf(1_000_000_000L).equals(h.getNanoCPUs())||!Long.valueOf(128).equals(h.getPidsLimit())
                ||h.getDevices()!=null&&h.getDevices().length>0||h.getCapAdd()!=null&&h.getCapAdd().length>0||h.getCapDrop()==null||!Arrays.asList(h.getCapDrop()).equals(List.of(Capability.ALL))
                ||h.getSecurityOpts()==null||!h.getSecurityOpts().contains("no-new-privileges:true")||h.getBinds()!=null&&h.getBinds().length>0
                ||mounts==null||mounts.size()!=1||mounts.getFirst().getType()!=MountType.BIND||!jobRoot(c).resolve("input").toString().equals(mounts.getFirst().getSource())
                ||!("/input/source."+extension(c)).equals(mounts.getFirst().getTarget())||!Boolean.TRUE.equals(mounts.getFirst().getReadOnly())
                ||h.getTmpFs()==null||h.getTmpFs().size()!=1||!TMPFS.equals(h.getTmpFs().get("/tmp"))
                ||startedAt!=null&&!sameInstant(startedAt,row.getState().getStartedAt()))throw failure("PREVIEW_CONTAINER_IDENTITY_MISMATCH");
        return row;
    }
    private static boolean sameInstant(String left,String right) {
        try{return Instant.parse(left).equals(Instant.parse(right));}catch(RuntimeException invalid){return false;}
    }
    private static boolean stopped(InspectContainerResponse.ContainerState state) {
        if(state==null||!"exited".equals(state.getStatus())||!Boolean.FALSE.equals(state.getRunning())||!Boolean.FALSE.equals(state.getPaused())||!Boolean.FALSE.equals(state.getRestarting())||!Long.valueOf(0).equals(state.getPidLong()))return false;
        try{return Instant.parse(state.getFinishedAt()).isAfter(Instant.parse(state.getStartedAt()));}catch(RuntimeException invalid){return false;}
    }
    private PreviewData.Stop stop(Command c,Physical p) {
        if(p==null||p.containerId()==null)return null;
        var row=exact(c,p.containerId(),p.startedAt());
        if(Boolean.TRUE.equals(row.getState().getRunning()))docker.stopContainerCmd(row.getId()).withTimeout(5).exec();
        row=exact(c,p.containerId(),p.startedAt());if(!stopped(row.getState())||p.startedAt()==null)return null;
        var active=activeReads.get(c.commandId());
        if(active!=null) {
            active.close();
            try{if(!active.done.await(5,TimeUnit.SECONDS)||!active.quiescent())return null;}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();return null;}
            activeReads.remove(c.commandId(),active);
        }
        var executions=reads.getOrDefault(c.commandId(),List.of());
        for(String exec:executions)try{if(!Boolean.FALSE.equals(docker.inspectExecCmd(exec).exec().isRunning()))return null;}catch(NotFoundException retired){if(!stopped(row.getState()))return null;}
        return new PreviewData.Stop(row.getId(),executions.isEmpty()?null:executions.getLast(),row.getImageId(),Instant.parse(p.startedAt()),true,true,true,Instant.parse(row.getState().getFinishedAt()));
    }
    @Override public Result render(Command c,Path source,Path output,Guard guard) {
        command(c);boolean invoked=false;Physical localPhysical=null;
        try {
            guard.check();runtime();paths(c,source,output);guard.check();
            try{docker.inspectContainerCmd(name(c)).exec();throw failure("PREVIEW_RECONCILIATION_REQUIRED");}catch(NotFoundException absent){}
            var host=HostConfig.newHostConfig().withRuntime("runsc").withNetworkMode("none").withReadonlyRootfs(true)
                    .withMemory(PreviewRuntimePolicy.MEMORY).withMemorySwap(PreviewRuntimePolicy.MEMORY).withNanoCPUs(1_000_000_000L).withPidsLimit(128L)
                    .withCapDrop(Capability.ALL).withSecurityOpts(List.of("no-new-privileges:true"))
                    .withMounts(List.of(new Mount().withType(MountType.BIND).withSource(source.toString()).withTarget("/input/source."+extension(c)).withReadOnly(true)))
                    .withTmpFs(Map.of("/tmp",TMPFS)).withLogConfig(new LogConfig(LogConfig.LoggingType.JSON_FILE,Map.of("max-size","1m","max-file","1")));
            invoked=true;
            var created=docker.createContainerCmd(policy.image()).withName(name(c)).withUser("10001:10001").withEntrypoint(ENTRY).withCmd(args(c)).withHostConfig(host).withLabels(labels(c)).exec();
            localPhysical=new Physical(created.getId(),null);
            guard.created(created.getId());guard.check();guard.startRequested();docker.startContainerCmd(created.getId()).exec();
            var row=exact(c,created.getId(),null);String startedAt=row.getState().getStartedAt();localPhysical=new Physical(created.getId(),startedAt);guard.started(startedAt);
            long deadline=Math.min(System.nanoTime()+TimeUnit.SECONDS.toNanos(120),System.nanoTime()+Math.max(0,Duration.between(Instant.now(),c.deadlineAt()).toNanos()));
            Properties manifest=null;
            while(!stopped(row.getState())&&System.nanoTime()<deadline) {
                guard.check();manifest=manifest(c,row.getId(),guard);if(manifest!=null)break;
                Thread.sleep(200);row=exact(c,created.getId(),startedAt);
            }
            Result observed=null;
            if(manifest!=null){guard.check();observed=output(c,output,manifest,null,guard);}
            var stop=stop(c,new Physical(created.getId(),startedAt));if(stop==null)throw failure("PREVIEW_STOP_UNCONFIRMED");
            if(observed!=null)return withStop(observed,stop);
            if(Boolean.TRUE.equals(row.getState().getOOMKilled()))return failed("PREVIEW_RESOURCE_EXHAUSTED",false,stop);
            return failed(stopped(row.getState())?"PREVIEW_CONVERSION_FAILED":"PREVIEW_TIMEOUT",false,stop);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw failure("PREVIEW_RESULT_UNKNOWN");}
        catch(IOException failed){throw failure("PREVIEW_STAGING_UNAVAILABLE");}
        finally {
            // Best effort never releases durable capacity. Only the caller's committed stop receipt can do that.
            if(invoked)try {
                // Loss of the database/authority must not prevent stopping our known exact physical instance.
                var p=localPhysical==null?guard.physical():localPhysical;
                if(p!=null&&p.startedAt()==null){var row=exact(c,p.containerId(),null);String at=row.getState().getStartedAt();if(at!=null&&!at.startsWith("0001"))p=new Physical(p.containerId(),at);}
                if(p!=null&&p.startedAt()!=null)stop(c,p);
            }catch(RuntimeException uncertain){}
        }
    }
    @Override public Result reconcile(Command c,Path output,Guard guard) {
        command(c);var p=guard.physical();
        if(p==null||p.containerId()==null) {
            if(guard.startWasRequested())return null;
            try {
                var row=exact(c,name(c),null);String at=row.getState().getStartedAt();boolean began=at!=null&&!at.startsWith("0001");
                guard.observed(new Physical(row.getId(),began?at:null),began||guard.startWasRequested());p=guard.physical();
            }catch(NotFoundException absent){return failed("PREVIEW_NOT_STARTED",true,new PreviewData.Stop(null,null,policy.image(),null,true,true,true,Instant.now()));}
        }
        var row=exact(c,p.containerId(),p.startedAt());
        if(p.startedAt()==null) {
            String at=row.getState().getStartedAt();
            if(at!=null&&!at.startsWith("0001")){guard.observed(new Physical(p.containerId(),at),true);p=guard.physical();}
            else if(!guard.startWasRequested()&&"created".equals(row.getState().getStatus()))return failed("PREVIEW_NOT_STARTED",true,new PreviewData.Stop(row.getId(),null,row.getImageId(),null,true,true,true,Instant.now()));
            else return null;
        }
        Result observed=null;
        try{var manifest=manifest(c,row.getId(),guard);if(manifest!=null)observed=output(c,output,manifest,null,guard);}
        catch(IOException|RuntimeException lost){/* Reconciliation never runs the conversion again. */}
        var stop=stop(c,p);if(stop==null)return null;
        return observed==null?failed("PREVIEW_RESULT_UNAVAILABLE",true,stop):withStop(observed,stop);
    }
    private Properties manifest(Command c,String container,Guard guard)throws IOException {
        var bytes=new ByteArrayOutputStream();
        if(!read(c,container,"manifest.properties",16384,bytes,guard))return null;
        Properties manifest=new Properties();manifest.load(new StringReader(bytes.toString(StandardCharsets.UTF_8)));return manifest;
    }
    private Result output(Command c,Path directory,Properties manifest,PreviewData.Stop stop,Guard guard)throws IOException {
        if("FAILED".equals(manifest.getProperty("state"))) {
            String code=manifest.getProperty("errorCode");if(code==null||!code.matches("PREVIEW_[A-Z_]{1,80}"))code="PREVIEW_CONVERSION_FAILED";
            return failed(code,false,stop);
        }
        if(!"READY".equals(manifest.getProperty("state")))return failed("PREVIEW_CONVERSION_FAILED",false,stop);
        try {
            String format=manifest.getProperty("format"),mime=manifest.getProperty("mediaType"),sha=manifest.getProperty("sha256");
            if(!Set.of("PDF","IMAGE","TEXT","HTML_STATIC").contains(format)||sha==null||!sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
            String expectedFormat=switch(c.sourceFormat()) {
                case "PDF","DOCX","XLSX","PPTX"->"PDF";case "PNG","JPEG","GIF","WEBP"->"IMAGE";
                case "HTML"->"HTML_STATIC".equals(c.requestedFormat())?"HTML_STATIC":"TEXT";default->"TEXT";
            };
            if(!expectedFormat.equals(format))throw new IllegalArgumentException();
            long bytes=Long.parseLong(manifest.getProperty("bytes"));if(bytes<0||bytes>PreviewData.MAX_BYTES)throw new IllegalArgumentException();
            String expectedMime=switch(format){case "PDF"->"application/pdf";case "IMAGE"->"image/png";case "TEXT"->"text/plain;charset=UTF-8";default->"text/html;charset=UTF-8";};
            if(!expectedMime.equals(mime))throw new IllegalArgumentException();
            Integer pages=manifest.getProperty("pages")==null?null:Integer.valueOf(manifest.getProperty("pages"));
            if("PDF".equals(format)&&(pages==null||pages<1||pages>200))throw new IllegalArgumentException();
            boolean source=Boolean.parseBoolean(manifest.getProperty("sourceReferenced"));
            if(source) {
                if(!Set.of("PDF","TEXT","HTML","SVG").contains(c.sourceFormat())||!Set.of("PDF","TEXT").contains(format)||bytes!=c.sourceBytes()||!sha.equals(c.sourceSha256()))throw new IllegalArgumentException();
                return new Result("READY",format,mime,bytes,sha,pages,null,true,null,false,stop);
            }
            String file=switch(format){case "PDF"->"content.pdf";case "IMAGE"->"content.png";case "HTML_STATIC"->"content.html";default->throw new IllegalArgumentException();};
            if(!file.equals(manifest.getProperty("file")))throw new IllegalArgumentException();
            if(!directory.toAbsolutePath().normalize().equals(jobRoot(c).resolve("output"))||Files.isSymbolicLink(directory))throw new IllegalArgumentException();
            Files.createDirectories(directory);Path content=directory.resolve(file);
            if(!Files.exists(content,LinkOption.NOFOLLOW_LINKS)) {
                Path pending=directory.resolve(file+".pending");
                if(Files.exists(pending,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unresolved preview output transfer");
                try(var out=Files.newOutputStream(pending,StandardOpenOption.CREATE_NEW)) {
                    if(!read(c,name(c),file,bytes,out,guard))throw new IOException("Preview result missing");
                }
                if(Files.size(pending)!=bytes||!hash(pending).equals(sha))throw new IOException("Preview length or hash mismatch");
                Files.move(pending,content,StandardCopyOption.ATOMIC_MOVE);
            }
            if(Files.isSymbolicLink(content)||!Files.isRegularFile(content,LinkOption.NOFOLLOW_LINKS)||Files.size(content)!=bytes||!hash(content).equals(sha))throw new IllegalArgumentException();
            return new Result("READY",format,mime,bytes,sha,pages,content,false,null,false,stop);
        }catch(RuntimeException invalid){return failed("PREVIEW_OUTPUT_REJECTED",false,stop);}
    }
    /** Docker archive/cp cannot expose tmpfs reliably. A fixed non-shell read stays in its mount namespace. */
    private boolean read(Command c,String container,String file,long max,OutputStream destination,Guard guard)throws IOException {
        if(!Set.of("manifest.properties","content.pdf","content.png","content.html").contains(file)||max<0||max>PreviewData.MAX_BYTES)throw new IOException("Invalid fixed preview output");
        String exec=docker.execCreateCmd(container).withAttachStdout(true).withAttachStderr(true).withTty(false).withCmd("/usr/bin/cat","/tmp/preview/"+file).exec().getId();
        guard.execCreated(exec);
        reads.computeIfAbsent(c.commandId(),ignored->Collections.synchronizedList(new ArrayList<>())).add(exec);
        var capture=new ReadCapture(destination,max);activeReads.put(c.commandId(),capture);
        try {
            docker.execStartCmd(exec).withDetach(false).withTty(false).exec(capture);
            if(!capture.done.await(10,TimeUnit.SECONDS))throw new IOException("Preview output callback incomplete");
            capture.close();
            if(!capture.quiescent()||capture.failure!=null)throw new IOException("Preview output transfer failed");
            var result=docker.inspectExecCmd(exec).exec();
            if(!Boolean.FALSE.equals(result.isRunning()))throw new IOException("Preview read still running");
            return Long.valueOf(0).equals(result.getExitCodeLong());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Preview read interrupted",interrupted);}
        finally {capture.close();if(capture.quiescent())activeReads.remove(c.commandId(),capture);}
    }
    private static final class ReadCapture extends ResultCallback.Adapter<Frame> {
        final CountDownLatch done=new CountDownLatch(1);final AtomicInteger callbacks=new AtomicInteger();
        final OutputStream destination;final long max;long bytes;volatile Throwable failure;volatile boolean closed;
        ReadCapture(OutputStream destination,long max){this.destination=destination;this.max=max;}
        @Override public void onNext(Frame frame) {
            callbacks.incrementAndGet();
            try {
                if(frame.getStreamType()!=StreamType.STDOUT)return;
                synchronized(this){if(failure!=null)return;bytes+=frame.getPayload().length;if(bytes>max)throw new IOException("Preview output limit");destination.write(frame.getPayload());}
            }catch(Throwable failed){failure=failed;}finally{callbacks.decrementAndGet();}
        }
        @Override public void onError(Throwable error){failure=error;done.countDown();}
        @Override public void onComplete(){done.countDown();}
        @Override public void close(){try{super.close();closed=true;}catch(IOException failed){failure=failed;}}
        boolean quiescent(){return done.getCount()==0&&callbacks.get()==0&&closed;}
    }
    private static Result failed(String code,boolean retryable,PreviewData.Stop stop){return new Result("FAILED",null,null,0,null,null,null,false,code,retryable,stop);}
    private static Result withStop(Result r,PreviewData.Stop stop){return new Result(r.state(),r.format(),r.mediaType(),r.bytes(),r.sha256(),r.pages(),r.output(),r.sourceReferenced(),r.errorCode(),r.retryable(),stop);}
    private static String hash(Path path)throws IOException {
        try {var sha=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)sha.update(buffer,0,n);}return HexFormat.of().formatHex(sha.digest());}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    @Override public void retire(Command c,PreviewData.Stop stop) {
        if(stop==null||!stop.confirmed())throw failure("PREVIEW_STOP_UNCONFIRMED");if(stop.containerId()==null)return;
        try {
            var row=exact(c,stop.containerId(),stop.startedAt()==null?null:stop.startedAt().toString());
            if(stop.startedAt()==null){if(!"created".equals(row.getState().getStatus()))throw failure("PREVIEW_STOP_UNCONFIRMED");}
            else if(!stopped(row.getState())||!stop.finishedAt().equals(Instant.parse(row.getState().getFinishedAt())))throw failure("PREVIEW_STOP_UNCONFIRMED");
            docker.removeContainerCmd(row.getId()).withForce(false).exec();
            reads.remove(c.commandId());activeReads.remove(c.commandId());
        }catch(NotFoundException retired){/* Immutable exact stop receipt already committed. */}
    }
    @Override public void close(){try{docker.close();}catch(IOException error){throw new IllegalStateException(error);}}
}
