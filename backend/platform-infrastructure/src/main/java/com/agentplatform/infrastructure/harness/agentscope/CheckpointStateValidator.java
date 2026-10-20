package com.agentplatform.infrastructure.harness.agentscope;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JacksonJsonCodec;
import java.nio.charset.StandardCharsets;

/** Validate the pinned private codec before a recovery is queued or acknowledged. */
public final class CheckpointStateValidator {
    private CheckpointStateValidator() {}
    public static void validate(byte[] bytes,String user,String conversation) {
        var value=new JacksonJsonCodec().fromJson(new String(bytes,StandardCharsets.UTF_8),AgentState.class);
        if(value==null||!user.equals(value.getUserId())||!conversation.equals(value.getSessionId())||value.getContext()==null)
            throw new IllegalArgumentException("CHECKPOINT_STATE_INVALID");
        for(var message:value.getContext())if(message==null||message.getRole()==null||message.getContent()==null)
            throw new IllegalArgumentException("CHECKPOINT_STATE_INVALID");
    }
}
