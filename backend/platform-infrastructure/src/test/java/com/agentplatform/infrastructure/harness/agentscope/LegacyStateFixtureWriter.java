package com.agentplatform.infrastructure.harness.agentscope;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JacksonJsonCodec;
import java.nio.file.Files;
import java.nio.file.Path;

/** Standalone writer launched with the old Jackson2 classpath to create migration evidence. */
public final class LegacyStateFixtureWriter {
    public static void main(String[] args) throws Exception {
        var state = AgentState.builder()
                .userId("0199db83-0000-7000-8000-000000000002")
                .sessionId("0199db83-0000-7000-8000-000000000003")
                .replyId("legacy-fixed-reply").summary("R0.3-private-state").build();
        Files.writeString(Path.of(args[0]), new JacksonJsonCodec().toJson(state));
        System.out.println("Legacy writer Jackson=" + com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION);
    }
}
