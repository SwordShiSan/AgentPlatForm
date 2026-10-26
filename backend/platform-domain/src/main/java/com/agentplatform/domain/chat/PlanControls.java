package com.agentplatform.domain.chat;

import com.agentplatform.types.harness.HarnessData.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Platform control metadata only: none of these capabilities grants workspace or business tool access. */
public final class PlanControls {
    private PlanControls() {}
    public record Write(String title, String markdown) {
        public Write {
            text(title, 1024); text(markdown, 131072);
            if(title.getBytes(StandardCharsets.UTF_8).length+markdown.getBytes(StandardCharsets.UTF_8).length>131072)throw new IllegalArgumentException("PLAN_BYTES_LIMIT");
        }
    }
    public record Review() {}
    public record Option(String id, String label) {
        public Option { text(id, 128); text(label, 1024); }
    }
    public record Question(String question, boolean optional, List<Option> options) {
        public Question {
            text(question, 32768);
            options = List.copyOf(java.util.Objects.requireNonNull(options, "QUESTION_OPTIONS_REQUIRED"));
            if (options.size() > 16) throw new IllegalArgumentException("QUESTION_OPTIONS_LIMIT");
            var ids = new HashSet<String>();
            for (Option option : options) if (!ids.add(option.id())) throw new IllegalArgumentException("QUESTION_OPTION_DUPLICATE");
            int bytes = question.getBytes(StandardCharsets.UTF_8).length;
            for (Option option : options) bytes += option.id().getBytes(StandardCharsets.UTF_8).length + option.label().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 32768) throw new IllegalArgumentException("QUESTION_BYTES_LIMIT");
        }
    }
    private static void text(String value, int bytes) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > bytes)
            throw new IllegalArgumentException("CONTROL_TEXT_INVALID");
    }
    private static Map<String, Object> object(Map<String, Object> properties, String... required) {
        return Map.of("type", "object", "properties", properties, "required", List.of(required), "additionalProperties", false);
    }
    private static Map<String, Object> string(int max) { return Map.of("type", "string", "minLength", 1, "maxLength", max); }
    public static final List<ToolDefinition> DEFINITIONS = List.of(
            new ToolDefinition("platform_plan_write", "Save a structured plan revision to the platform. Text planning only; grants no tool permissions.",
                    ToolCategory.CONTROL, object(Map.of("title", string(1024), "markdown", string(131072)), "title", "markdown"), Write.class, null),
            new ToolDefinition("platform_plan_review", "Ask the user to review the current saved plan. Approval allows only text continuation.",
                    ToolCategory.CONTROL, object(Map.of()), Review.class, null),
            new ToolDefinition("platform_ask_user", "Ask a structured clarification. Required questions stop for a durable answer; optional questions do not block.",
                    ToolCategory.CONTROL, object(Map.of("question", string(32768), "optional", Map.of("type", "boolean"),
                    "options", Map.of("type", "array", "maxItems", 16, "items", object(Map.of("id", string(128), "label", string(1024)), "id", "label"))),
                    "question", "optional", "options"), Question.class, null));
    public static final Set<String> NAMES = Set.of("platform_plan_write", "platform_plan_review", "platform_ask_user");
}
