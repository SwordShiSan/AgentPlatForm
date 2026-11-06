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
    public record Input(@NotNull String text, @Size(max=10) List<com.agentplatform.types.AttachmentReference> attachments) {}
    public record Options(String modelId,
                          String credentialSource,
                          Boolean planMode,
                          String permissionMode, String workspaceId, String expectedEffectiveConfigHash) {
        public Options(String modelId,String credentialSource,Boolean planMode,String permissionMode){this(modelId,credentialSource,planMode,permissionMode,null,null);}
        public Options(String modelId,String credentialSource,Boolean planMode,String permissionMode,String workspaceId){this(modelId,credentialSource,planMode,permissionMode,workspaceId,null);}
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
        private Boolean planEnabled;
        private String approvalMode;
        private boolean planPresent, approvalPresent;
        private com.agentplatform.types.WorkbenchPreferences.Layout layout;
        private List<com.agentplatform.types.WorkbenchPreferences.RecentFile> recent;
        private boolean layoutPresent,recentPresent;
        @JsonSetter("defaultModelId")
        public void setDefaultModelId(String value) { modelId = value; modelPresent = true; }
        @JsonSetter("defaultCredentialSource")
        public void setDefaultCredentialSource(String value) { source = value; sourcePresent = true; }
        @JsonSetter("defaultPlanEnabled")
        public void setDefaultPlanEnabled(Boolean value) { planEnabled = value; planPresent = true; }
        @JsonSetter("defaultApprovalMode")
        public void setDefaultApprovalMode(String value) { approvalMode = value; approvalPresent = true; }
        @JsonSetter("workbenchLayout") public void setWorkbenchLayout(com.agentplatform.types.WorkbenchPreferences.Layout value){layout=value;layoutPresent=true;}
        @JsonSetter("recentFiles") public void setRecentFiles(List<com.agentplatform.types.WorkbenchPreferences.RecentFile> value){recent=value;recentPresent=true;}
        public Map<String, Object> values() {
            var result = new LinkedHashMap<String, Object>();
            if (modelPresent) result.put("defaultModelId", modelId);
            if (sourcePresent) result.put("defaultCredentialSource", source);
            if (planPresent) result.put("defaultPlanEnabled", planEnabled);
            if (approvalPresent) result.put("defaultApprovalMode", approvalMode);
            if (layoutPresent) result.put("workbenchLayout",layout);
            if (recentPresent) result.put("recentFiles",recent);
            return result;
        }
    }
}
