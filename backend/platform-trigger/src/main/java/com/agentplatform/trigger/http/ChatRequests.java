package com.agentplatform.trigger.http;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import com.fasterxml.jackson.annotation.JsonSetter;

public final class ChatRequests {
    private ChatRequests() {}
    public record Login(@NotNull @Size(max = 256) String username,
                        @NotNull @Size(max = 128) String password) {
        @Override public String toString() { return "Login[REDACTED]"; }
    }
    public record Input(@NotNull String text, List<Object> attachments) {}
    public record Options(String modelId,
                          String credentialSource,
                          Boolean planMode,
                          String permissionMode, String workspaceId) {
        public Options(String modelId,String credentialSource,Boolean planMode,String permissionMode){this(modelId,credentialSource,planMode,permissionMode,null);}
    }
    public record Task(@NotNull @Valid Input input, @NotNull @Valid Options options) {}
    public static final class Conversation {
        private String title;
        private String workspaceId;
        @Valid private Task initialTask;
        @JsonSetter("title")
        public void setTitle(String value) { title = value; }
        @JsonSetter("workspaceId")
        public void setWorkspaceId(String value) { workspaceId = value; }
        @JsonSetter("initialTask")
        public void setInitialTask(Task value) {
            if (value == null) throw new IllegalArgumentException("initialTask must be an object when present");
            initialTask = value;
        }
        public String title() { return title; }
        public String workspaceId() { return workspaceId; }
        public Task initialTask() { return initialTask; }
    }
    public record Credential(@NotNull String apiKey) {
        @Override public String toString() { return "Credential[REDACTED]"; }
    }
    public record Empty() {}
    public static final class PreferencePatch {
        private String modelId;
        private String source;
        private boolean modelPresent;
        private boolean sourcePresent;
        @JsonSetter("defaultModelId")
        public void setDefaultModelId(String value) { modelId = value; modelPresent = true; }
        @JsonSetter("defaultCredentialSource")
        public void setDefaultCredentialSource(String value) { source = value; sourcePresent = true; }
        public Map<String, Object> values() {
            var result = new LinkedHashMap<String, Object>();
            if (modelPresent) result.put("defaultModelId", modelId);
            if (sourcePresent) result.put("defaultCredentialSource", source);
            return result;
        }
    }
}
