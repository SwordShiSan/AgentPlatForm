package com.agentplatform.trigger.http;

import com.agentplatform.api.chat.WorkspaceSettingsApi;
import com.agentplatform.domain.chat.ChatFailure;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("chat-local")
@ConditionalOnProperty(name = "platform.personal-config.enabled", havingValue = "true")
@RequestMapping("/api/v1/workspaces/{id}/settings")
public final class WorkspaceSettingsController {
    private final WorkspaceSettingsApi settings;
    public WorkspaceSettingsController(WorkspaceSettingsApi settings) { this.settings = settings; }

    public static final class PatchBody {
        private String model, credential, permission;
        private Boolean plan;
        private boolean hasModel, hasCredential, hasPlan, hasPermission;
        @JsonSetter("defaultModelId") public void model(String value) { model=value; hasModel=true; }
        @JsonSetter("defaultCredentialSource") public void credential(String value) { credential=value; hasCredential=true; }
        @JsonSetter("defaultPlanEnabled") public void plan(Boolean value) { plan=value; hasPlan=true; }
        @JsonSetter("defaultApprovalMode") public void permission(String value) { permission=value; hasPermission=true; }
        WorkspaceSettingsApi.Patch patch() { return new WorkspaceSettingsApi.Patch(hasModel,model,hasCredential,credential,hasPlan,plan,hasPermission,permission); }
    }

    @GetMapping ResponseEntity<WorkspaceSettingsApi.Settings> read(HttpServletRequest request, @PathVariable("id") String id) {
        return response(settings.read(RequestIdentity.caller(request),id));
    }
    @PatchMapping ResponseEntity<WorkspaceSettingsApi.Settings> update(HttpServletRequest request, @PathVariable("id") String id,
                                                                        @RequestBody PatchBody body) {
        String match=request.getHeader("If-Match");
        if (match==null) throw new ChatFailure(428,"VERSION_REQUIRED");
        long version;
        try {
            if (!match.matches("\"[0-9]+\"")) throw new NumberFormatException();
            version=Long.parseLong(match.substring(1,match.length()-1));
        } catch (NumberFormatException invalid) { throw new ChatFailure(400,"INVALID_VERSION"); }
        return response(settings.update(RequestIdentity.caller(request),id,version,body.patch()));
    }
    private static ResponseEntity<WorkspaceSettingsApi.Settings> response(WorkspaceSettingsApi.Settings settings) {
        return ResponseEntity.ok().eTag("\""+settings.version()+"\"").header("Cache-Control","no-store").body(settings);
    }
}
