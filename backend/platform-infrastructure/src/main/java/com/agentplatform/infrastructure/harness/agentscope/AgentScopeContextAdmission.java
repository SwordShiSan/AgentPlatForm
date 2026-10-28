package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.types.harness.HarnessData.Message;
import com.agentplatform.types.harness.HarnessData.ToolDefinition;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Padded SDK estimate only. Supplier usage remains the settlement authority. */
public final class AgentScopeContextAdmission {
    private AgentScopeContextAdmission() {}

    public static long estimate(List<Message> history, List<ToolDefinition> definitions) {
        var codec = new PlatformMessageCodec();
        var messages = new ArrayList<>(history.stream().map(codec::encode).toList());
        String schemas = codec.json(definitions.stream().map(d -> Map.of(
                "name", d.name(), "description", d.description(), "parameters", d.inputSchema())).toList());
        messages.add(Msg.builder().role(MsgRole.SYSTEM).content(TextBlock.builder().text(schemas).build()).build());
        long sdk = TokenCounterUtil.calculateToken(messages);
        // The SDK's character estimate is optimistic for multilingual content.
        // Add multilingual codepoint padding without counting UTF-8 width twice.
        String content = codec.json(history) + schemas;
        long nonAscii = content.codePoints().filter(c -> c > 127)
                .mapToLong(c -> Character.charCount(c) == 2 ? 3 : 1).sum();
        return (sdk * 5 + 3) / 4 + nonAscii + 512;
    }

    public static void requireFits(List<Message> history, List<ToolDefinition> definitions) {
        if (estimate(history, definitions) > 7168) throw new ChatFailure(422, "CONTEXT_REQUIRED_TOO_LARGE");
    }
}
