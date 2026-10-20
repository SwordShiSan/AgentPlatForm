package com.agentplatform.app.chat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Host-driven acceptance windows; no control is exposed by the production composition. */
final class FixtureFaults {
    private FixtureFaults() {}
    private static final Set<String> POINTS=Set.of("claim-before-ack","ack-before-model","input-checkpoint-after-seal","model-checkpoint-after-seal","tool-checkpoint-after-seal","terminal-checkpoint-after-seal","response-before-round","round-before-checkpoint","checkpoint-before-seal","checkpoint-after-seal","tool-after-start","tool-before-result","tool-after-result");
    static boolean admitted(String point,boolean fixture,boolean realAcceptance,String profiles) {
        if(!POINTS.contains(point))return false;
        if(fixture)return true;
        boolean explicit=java.util.Arrays.stream(profiles.split(",")).map(String::strip).anyMatch("recovery-provider-acceptance"::equals);
        return realAcceptance&&explicit&&Set.of("input-checkpoint-after-seal","terminal-checkpoint-after-seal").contains(point);
    }
    static void pause(String point) {
        if(!point.equals(System.getenv("PLATFORM_CHAT_FIXTURE_FAULT")))return;
        if(!admitted(point,"true".equals(System.getenv("PLATFORM_CHAT_FIXTURE_PROVIDER")),
                "true".equals(System.getenv("PLATFORM_CHAT_REAL_RECOVERY_ACCEPTANCE")),System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE","")))return;
        if(!POINTS.contains(point))throw new IllegalArgumentException("Unknown fixture fault");
        try {
            Files.writeString(Path.of("/tmp/chat-fault-ready"),point);
            // No catch-and-success, no provider retry: the trusted host kills this exact process.
            while(true)Thread.sleep(250);
        }catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IllegalStateException("FIXTURE_INTERRUPTED");}
        catch(java.io.IOException failure){throw new IllegalStateException("FIXTURE_MARKER_UNAVAILABLE");}
    }
}
