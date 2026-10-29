package com.agentplatform.infrastructure.preview;

import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import com.agentplatform.infrastructure.workspace.SandboxPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/** All physical policy inputs are trusted operator configuration, never public preview parameters. */
public record PreviewRuntimePolicy(String image,Path stagingRoot,Path runtimeAttestation,String bundle) {
    public static final long MEMORY=512L*1024*1024;
    public PreviewRuntimePolicy {
        if(image==null||!image.matches("sha256:[a-f0-9]{64}")||bundle==null||!bundle.matches("[a-zA-Z0-9._-]{1,128}"))throw new IllegalArgumentException("Pinned renderer identity required");
        Objects.requireNonNull(stagingRoot);Objects.requireNonNull(runtimeAttestation);
        stagingRoot=stagingRoot.normalize();runtimeAttestation=runtimeAttestation.normalize();
        String root=stagingRoot.toString().replace('\\','/');
        if(!root.equals("/srv/agentplatform/previews")&&!root.matches("/srv/agentplatform/acceptance/r44-r53-[0-9a-z-]+/previews"))throw new IllegalArgumentException("Protected preview staging root required");
    }
    public String hash() {
        return RunnerSignatures.sha256(("preview-runtime-v1|"+image+"|"+bundle+"|"+SandboxPolicy.RUNSC_SHA256
                +"|systrap|uid10001|net-none|ro|caps-none|no-devices|one-readonly-input|memory512m-swap512m|cpu1|pids128|tmpfs256m|timeout120s|output100m|pages200|image40mp|zip500m|source-or-fixed-output|office26.2.6|jod5.1.1|pdfbox3.0.8|jsoup1.23.2|twelvemonkeys3.13.1")
                .getBytes(StandardCharsets.UTF_8));
    }
    /** Public preview representation contract: HTML is fetched as inert text for an isolated srcdoc view. */
    public static String mediaType(String format) {
        return switch(format){case "PDF"->"application/pdf";case "IMAGE"->"image/png";case "TEXT","HTML_STATIC"->"text/plain";
            default->throw new IllegalArgumentException("Unsupported preview representation");};
    }
}
