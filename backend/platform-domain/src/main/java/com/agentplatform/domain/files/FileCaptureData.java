package com.agentplatform.domain.files;

import java.util.Objects;
import java.util.UUID;

/** New signed family; the legacy upload/scan command bytes remain unchanged. */
public final class FileCaptureData {
    public static final String PROTOCOL="FILE_CAPTURE_V1";
    private FileCaptureData() {}
    public record Target(UUID id,UUID sourceOperationId,UUID spaceId,UUID workspaceId,UUID fileOperationId,
                         String path,long maxBytes,UUID versionId,UUID assetId,String objectKey) {
        public Target {
            Objects.requireNonNull(id);Objects.requireNonNull(sourceOperationId);Objects.requireNonNull(spaceId);Objects.requireNonNull(workspaceId);
            Objects.requireNonNull(fileOperationId);Objects.requireNonNull(versionId);Objects.requireNonNull(assetId);
            path=FileRules.path(path,false);
            if(maxBytes<0||maxBytes>104857600||objectKey==null||!objectKey.equals("spaces/"+spaceId+"/file-version/"+assetId+"/"+versionId+"/content"))
                throw new FileFailure(400,"INVALID_CAPTURE_TARGET");
        }
    }
    public record Command(String protocol,FileData.Command file,Target target) {
        public Command {
            if(!PROTOCOL.equals(protocol)||file==null||target==null||!"CAPTURE".equals(file.operation().kind())||file.upload()!=null
                    ||!target.fileOperationId().equals(file.operation().id())||!target.spaceId().equals(file.operation().spaceId())
                    ||!target.workspaceId().equals(file.operation().workspaceId()))throw new FileFailure(400,"INVALID_CAPTURE_COMMAND");
        }
    }
    public record Descriptor(long bytes,String sha256) {
        public Descriptor {if(bytes<0||bytes>104857600)throw new FileFailure(413,"FILE_TOO_LARGE");sha256=FileRules.sha256(sha256);}
    }
}
