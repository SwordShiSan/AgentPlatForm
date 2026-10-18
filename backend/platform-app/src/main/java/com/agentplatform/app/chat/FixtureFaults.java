package com.agentplatform.app.chat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Host-driven crash windows, accepted only for the explicit deterministic-provider fixture. */
final class FixtureFaults {
    private FixtureFaults() {}
    private static final Set<String> POINTS=Set.of("claim-before-ack","ack-before-model","response-before-round","round-before-checkpoint","checkpoint-before-seal","checkpoint-after-seal");
    static void pause(String point) {
        if(!"true".equals(System.getenv("PLATFORM_CHAT_FIXTURE_PROVIDER"))||!point.equals(System.getenv("PLATFORM_CHAT_FIXTURE_FAULT")))return;
        if(!POINTS.contains(point))throw new IllegalArgumentException("Unknown fixture fault");
        try {
            Files.writeString(Path.of("/tmp/chat-fault-ready"),point);
            // No catch-and-success, no provider retry: the trusted host kills this exact process.
            while(true)Thread.sleep(250);
        }catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IllegalStateException("FIXTURE_INTERRUPTED");}
        catch(java.io.IOException failure){throw new IllegalStateException("FIXTURE_MARKER_UNAVAILABLE");}
    }
}
