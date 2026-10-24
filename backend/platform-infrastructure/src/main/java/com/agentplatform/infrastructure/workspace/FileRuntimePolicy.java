package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.files.FileData.Command;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Operator-only policy; no path, image, device or executable comes from a browser. */
public record FileRuntimePolicy(String image,String quotaImage,String workspaceRoot,UUID mountUuid,String quotaDevice) {
    public FileRuntimePolicy {
        if(image==null||!image.matches("sha256:[a-f0-9]{64}")||quotaImage==null||!quotaImage.matches("sha256:[a-f0-9]{64}"))
            throw new IllegalArgumentException("Pinned file and quota helper images required");
        workspaceRoot=DockerWorkspaceAllocator.fixedWorkspaceRoot(workspaceRoot);Objects.requireNonNull(mountUuid);
        if(DockerWorkspaceAllocator.fixedQuotaDevice(quotaDevice)==null)throw new IllegalArgumentException("Verified XFS loop device required");
    }
    public String hash() {
        return RunnerSignatures.sha256(("file-stream-policy-v1|"+image+"|"+quotaImage+"|"+workspaceRoot+"|"+mountUuid+"|"+quotaDevice
                +"|uid10001|runc-trusted-file-only|ro|net-none|caps-none|no-devices|no-socket|memory64m-swap64m|cpu1|pids32|tmpfs8m|raw64k|header64k|nfc|nofollow|sha256|single-work-bind|scan-v1-100entries-28k|create-noreplace|require-loop-device|file-stream-v2-sha256-trailer32")
                .getBytes(StandardCharsets.UTF_8));
    }
    public String work(Command command) {return workspaceRoot+"/"+command.operation().spaceId()+"/"+command.operation().workspaceId()+"/work";}
}
