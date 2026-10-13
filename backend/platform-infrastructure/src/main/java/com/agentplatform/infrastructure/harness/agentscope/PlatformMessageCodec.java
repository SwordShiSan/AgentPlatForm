package com.agentplatform.infrastructure.harness.agentscope;

import com.agentplatform.types.harness.HarnessData.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.agentscope.core.message.*;
import io.agentscope.core.model.ChatResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Private Jackson2 + SDK boundary. No JsonNode or SDK message is returned to the platform. */
public final class PlatformMessageCodec {
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    public void validate(Round round) {
        if (!Set.of("stop", "tool_calls").contains(round.finishReason())) throw new IllegalArgumentException("MODEL_OUTPUT_TRUNCATED_OR_INVALID");
        if (round.text().isBlank() && round.calls().isEmpty()) throw new IllegalArgumentException("MODEL_EMPTY_RESPONSE");
        var ids = new HashSet<String>();
        for (Call call : round.calls()) {
            if (!ids.add(call.id())) throw new IllegalArgumentException("DUPLICATE_TOOL_CALL");
            json(call.arguments());
        }
    }
    public Msg encode(Message message) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (!message.text().isEmpty()) blocks.add(TextBlock.builder().text(message.text()).build());
        message.calls().forEach(call -> blocks.add(toolUse(call)));
        message.observations().forEach(result -> blocks.add(ToolResultBlock.text(result.text()).withIdAndName(result.callId(), result.name())
                .withState(result.status() == OperationStatus.SUCCEEDED ? ToolResultState.SUCCESS : result.status() == OperationStatus.DENIED ? ToolResultState.DENIED : ToolResultState.ERROR)));
        return Msg.builder().name(message.role() == Role.ASSISTANT ? "platform-runtime" : message.role().name().toLowerCase(java.util.Locale.ROOT))
                .role(MsgRole.valueOf(message.role().name())).content(blocks).build();
    }
    public Message decode(Msg message, Map<String, Call> definitions) {
        var text = new StringBuilder();
        List<Call> calls = new ArrayList<>();
        List<Observation> results = new ArrayList<>();
        for (ContentBlock block : message.getContent()) {
            if (block instanceof TextBlock t) text.append(t.getText());
            else if (block instanceof ToolUseBlock tool) calls.add(decodeCall(tool, definitions));
            else if (block instanceof ToolResultBlock result) {
                OperationStatus status = switch (result.getState()) {
                    case SUCCESS -> OperationStatus.SUCCEEDED;
                    case DENIED -> OperationStatus.DENIED;
                    default -> OperationStatus.UNKNOWN;
                };
                results.add(new Observation(result.getId(), result.getName(), result.getOutput().toString(), status));
            }
            else if (!(block instanceof ThinkingBlock)) throw new IllegalArgumentException("MODEL_BLOCK_UNSUPPORTED");
        }
        return new Message(Role.valueOf(message.getRole().name()), text.toString(), calls, results);
    }
    public Call decodeCall(ToolUseBlock tool, Map<String, Call> definitions) {
        Call definition = definitions.get(tool.getName());
        if (definition == null) throw new IllegalArgumentException("TOOL_NOT_REGISTERED");
        Map<String, Object> args = tool.getInput();
        if (tool.getContent() != null && !tool.getContent().isBlank()) {
            try { args = mapper.readValue(tool.getContent(), new TypeReference<Map<String, Object>>() {}); }
            catch (Exception error) { throw new IllegalArgumentException("MODEL_TOOL_ARGUMENTS_INVALID", error); }
        }
        if (args == null || !args.keySet().equals(definition.arguments().keySet())) throw new IllegalArgumentException("MODEL_TOOL_ARGUMENTS_INVALID");
        for (var entry : definition.arguments().entrySet()) {
            Object supplied = args.get(entry.getKey());
            if (supplied == null || !entry.getValue().getClass().isInstance(supplied)) throw new IllegalArgumentException("MODEL_TOOL_ARGUMENTS_INVALID");
        }
        return new Call(tool.getId(), tool.getName(), args, definition.readOnly());
    }
    public ToolUseBlock toolUse(Call call) {
        return ToolUseBlock.builder().id(call.id()).name(call.name()).input(call.arguments()).content(json(call.arguments())).build();
    }
    public ChatResponse response(Round round) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (!round.text().isEmpty()) blocks.add(TextBlock.builder().text(round.text()).build());
        round.calls().forEach(call -> blocks.add(toolUse(call)));
        return ChatResponse.builder().content(blocks).finishReason(round.finishReason()).build();
    }
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalArgumentException("STATE_SERIALIZATION_FAILED", error); }
    }
}
