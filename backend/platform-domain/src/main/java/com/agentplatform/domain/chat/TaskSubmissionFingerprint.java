package com.agentplatform.domain.chat;

import com.agentplatform.types.AttachmentReference;
import com.agentplatform.types.TaskPermissionData.Mode;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Presence-aware identity of the original request, before resolving changing defaults. */
public final class TaskSubmissionFingerprint {
    public static final short VERSION = 2;
    public record Request(String title, String text, UUID workspaceId, UUID modelId,
                          ConfigurationResolver.CredentialSource credentialSource, Boolean planMode,
                          Mode permissionMode, String expectedEffectiveConfigHash, List<AttachmentReference> attachments) {
        public Request { attachments = attachments == null ? List.of() : List.copyOf(attachments); }
    }
    private TaskSubmissionFingerprint() {}
    public static String compute(Request request) {
        try {
            var bytes=new ByteArrayOutputStream();
            try (var out=new DataOutputStream(bytes)) {
                out.writeShort(VERSION);write(out,request.title());write(out,request.text());
                write(out,request.workspaceId()==null?null:request.workspaceId().toString());
                write(out,request.modelId()==null?null:request.modelId().toString());
                write(out,request.credentialSource()==null?null:request.credentialSource().name());
                write(out,request.planMode()==null?null:request.planMode().toString());
                write(out,request.permissionMode()==null?null:request.permissionMode().name());
                write(out,request.expectedEffectiveConfigHash());out.writeInt(request.attachments().size());
                for (var ref:request.attachments()) {
                    write(out,ref.kind());write(out,ref.fileId());write(out,ref.versionId());write(out,ref.attachmentId());write(out,ref.extractionId());
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException("Could not hash request",impossible); }
    }
    private static void write(DataOutputStream out,String value) throws IOException {
        if(value==null){out.writeInt(-1);return;}
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);
    }
}
