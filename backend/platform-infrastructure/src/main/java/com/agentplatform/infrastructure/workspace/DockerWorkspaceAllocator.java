package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.workspace.WorkspaceAllocator;
import com.agentplatform.domain.workspace.WorkspaceData.Command;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

/** Trusted fixed helper only. Existing commands are inspected, never restarted or recreated. */
public final class DockerWorkspaceAllocator implements WorkspaceAllocator, AutoCloseable {
    private final DockerClient docker;
    private final String image;
    private final String root;
    private final UUID mountUuid;
    private final Device quotaDevice;
    private static final JsonMapper JSON=JsonMapper.builder().build();

    public DockerWorkspaceAllocator(String image,String root,UUID mountUuid) {
        this(image,root,mountUuid,null);
    }
    public DockerWorkspaceAllocator(String image,String root,UUID mountUuid,String quotaDevice) {
        if(image==null||!image.matches("sha256:[a-f0-9]{64}"))
            throw new IllegalArgumentException("Pinned helper and fixed trusted mount required");
        this.image=image;this.root=fixedWorkspaceRoot(root);this.mountUuid=Objects.requireNonNull(mountUuid);this.quotaDevice=fixedQuotaDevice(quotaDevice);
        var config=DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost("unix:///var/run/docker.sock").withApiVersion("1.45").build();
        var transport=new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost()).connectionTimeout(Duration.ofSeconds(2))
                .responseTimeout(Duration.ofSeconds(10)).maxConnections(4).build();
        docker=DockerClientImpl.getInstance(config,transport);
    }
    static String fixedWorkspaceRoot(String path) {
        if(path==null||!(path.equals("/srv/agentplatform/workspaces")
                ||path.matches("/srv/agentplatform/acceptance/r3[1234]-[a-z0-9-]+/workspaces")
                ||path.matches("/srv/agentplatform/acceptance/r44-[a-z0-9-]+/workspaces")))
            throw new IllegalArgumentException("Pinned helper and fixed trusted mount required");
        return path;
    }
    static Device fixedQuotaDevice(String path) {
        if(path==null||path.isEmpty())return null; // Desktop negative profile cannot allocate real XFS.
        if(!path.matches("/dev/loop[0-9]{1,5}"))throw new IllegalArgumentException("Only an operator-pinned loop device is allowed");
        return Device.parse(path+":"+path+":r");
    }
    private String name(Command c){return "ap-workspace-quota-"+c.commandId();}
    private Map<String,String> labels(Command c) {
        return Map.of("ap.role","workspace-quota","ap.command",c.commandId().toString(),"ap.operation",c.operationId().toString(),
                "ap.workspace",c.workspaceId().toString(),"ap.space",c.spaceId().toString(),"ap.generation",Long.toString(c.generation()),
                "ap.fence",Long.toString(c.fence()));
    }
    private String[] arguments(Command c){return new String[]{"--space",c.spaceId().toString(),"--workspace",c.workspaceId().toString(),
            "--operation",c.operationId().toString(),"--command",c.commandId().toString(),"--project",Integer.toString(c.projectId()),"--mount-uuid",mountUuid.toString()};}
    private boolean exists(Command c) {
        try {var actual=docker.inspectContainerCmd(name(c)).exec();
            if(!image.equals(actual.getImageId())||actual.getConfig().getLabels()==null||!actual.getConfig().getLabels().entrySet().containsAll(labels(c).entrySet())
                    ||!Arrays.equals(arguments(c),actual.getConfig().getCmd()))throw new IllegalStateException("Helper identity conflict");
            var binds=actual.getHostConfig().getBinds();
            if(binds==null||binds.length!=1||!root.equals(binds[0].getPath())||!"/quota".equals(binds[0].getVolume().getPath()))
                throw new IllegalStateException("Helper mount conflict");
            var devices=actual.getHostConfig().getDevices();
            if(quotaDevice==null?(devices!=null&&devices.length!=0):(devices==null||devices.length!=1||!quotaDevice.equals(devices[0])))
                throw new IllegalStateException("Helper quota device conflict");
            return true;
        }catch(NotFoundException absent){return false;}
    }
    @Override public String provision(Command c) {
        if(exists(c))throw new IllegalStateException("Existing physical command requires inspection");
        var host=HostConfig.newHostConfig().withNetworkMode("none").withReadonlyRootfs(true).withAutoRemove(false)
                .withBinds(new Bind(root,new Volume("/quota"),AccessMode.rw)).withMemory(64L*1024*1024).withMemorySwap(64L*1024*1024)
                .withPidsLimit(32L).withNanoCPUs(250_000_000L).withCapDrop(Capability.ALL)
                .withCapAdd(Capability.SYS_ADMIN,Capability.CHOWN,Capability.SETUID,Capability.SETGID)
                .withSecurityOpts(List.of("no-new-privileges:true")).withTmpFs(Map.of("/tmp","rw,noexec,nosuid,size=8m"))
                .withLogConfig(new LogConfig(LogConfig.LoggingType.JSON_FILE,Map.of("max-size","10m","max-file","1")));
        if(quotaDevice!=null)host.withDevices(quotaDevice);
        String id=docker.createContainerCmd(image).withName(name(c)).withLabels(labels(c)).withHostConfig(host)
                .withUser("0:0").withCmd(arguments(c)).exec().getId();
        docker.startContainerCmd(id).exec();
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(80);
        while(System.nanoTime()<end) {
            Result result=inspect(c);
            if(result!=null) {
                if("COMMITTED".equals(result.state()))return result.allocationHash();
                if("FAILED".equals(result.state())&&"NOT_STARTED".equals(result.effectState()))throw new RejectedBeforeEffect(result.errorCode());
                throw new IllegalStateException("Physical allocation requires reconciliation");
            }
            try{Thread.sleep(200);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Helper observation interrupted");}
        }
        throw new IllegalStateException("Helper completion not confirmed");
    }
    @Override public Result inspect(Command c) {
        if(!exists(c))return null;
        var actual=docker.inspectContainerCmd(name(c)).exec();
        if(Boolean.TRUE.equals(actual.getState().getRunning())||"created".equals(actual.getState().getStatus()))return null;
        var output=new ByteArrayOutputStream();
        try(var callback=new ResultCallback.Adapter<Frame>() {
            @Override public void onNext(Frame frame){if(output.size()+frame.getPayload().length>65536)throw new IllegalStateException("Helper result too large");output.writeBytes(frame.getPayload());}
        }) {
            if(!docker.logContainerCmd(name(c)).withStdOut(true).withStdErr(false).withTail(1).exec(callback).awaitCompletion(5,TimeUnit.SECONDS))
                throw new IllegalStateException("Helper result unavailable");
            var result=JSON.readValue(output.toString(StandardCharsets.UTF_8).strip(),Result.class);
            if("COMMITTED".equals(result.state())&&"ALLOCATED".equals(result.effectState())&&result.allocationHash()!=null
                    &&result.allocationHash().matches("[a-f0-9]{64}")&&Long.valueOf(0).equals(actual.getState().getExitCodeLong()))return result;
            if("FAILED".equals(result.state())&&"NOT_STARTED".equals(result.effectState())&&result.errorCode()!=null&&result.errorCode().matches("[A-Z_]{1,64}"))return result;
            return new Result("UNKNOWN","MAY_HAVE_STARTED",null,"WORKSPACE_PROVISIONING_UNKNOWN");
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Helper inspection interrupted");}
        catch(java.io.IOException invalid){throw new IllegalStateException("Helper result unreadable");}
    }
    @Override public void releaseCompletedHelper(Command command) {
        if(!exists(command))return;
        var actual=docker.inspectContainerCmd(name(command)).exec();
        if(!"exited".equals(actual.getState().getStatus())||Boolean.TRUE.equals(actual.getState().getRunning()))
            throw new IllegalStateException("Helper termination is not confirmed");
        docker.removeContainerCmd(actual.getId()).withForce(false).withRemoveVolumes(false).exec();
    }
    @Override public void close() throws java.io.IOException {docker.close();}
}
