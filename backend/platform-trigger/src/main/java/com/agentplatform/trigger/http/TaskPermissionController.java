package com.agentplatform.trigger.http;

import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.types.TaskPermissionData.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("chat-local")
@ConditionalOnProperty(name="platform.chat.permissions-enabled", havingValue="true")
@RequestMapping("/api/v1/tasks/{id}")
public final class TaskPermissionController {
    private final ChatApi chat;
    public TaskPermissionController(ChatApi chat) { this.chat = chat; }
    private static String key(HttpServletRequest request) {
        String value = request.getHeader("Idempotency-Key");
        if (value == null) throw new ChatFailure(428, "IDEMPOTENCY_KEY_REQUIRED");
        return value;
    }
    private static long version(HttpServletRequest request) {
        String raw = request.getHeader("If-Match");
        if (raw == null) throw new ChatFailure(428, "VERSION_REQUIRED");
        try {
            if (!raw.matches("\"[1-9][0-9]*\"")) throw new NumberFormatException();
            return Long.parseLong(raw.substring(1, raw.length()-1));
        } catch (NumberFormatException invalid) { throw new ChatFailure(400, "INVALID_VERSION"); }
    }
    private static ResponseEntity<View> response(View view) {
        return ResponseEntity.ok().eTag("\"" + view.version() + "\"").header("Cache-Control", "no-store").body(view);
    }
    @GetMapping("/permissions")
    ResponseEntity<View> read(HttpServletRequest request, @PathVariable("id") String id) { return response(chat.permissions(RequestIdentity.caller(request), id)); }
    @PatchMapping("/permissions")
    ResponseEntity<View> mode(HttpServletRequest request, @PathVariable("id") String id, @RequestBody Patch body) {
        return response(chat.permissionMode(RequestIdentity.caller(request), id, key(request), version(request), body.mode()));
    }
    @GetMapping("/permission-scopes")
    Scopes scopes(HttpServletRequest request, @PathVariable("id") String id, @RequestParam(name="cursor",required=false) String cursor) { return chat.permissionScopes(RequestIdentity.caller(request), id,cursor); }
    @PostMapping("/grants")
    ResponseEntity<View> grant(HttpServletRequest request, @PathVariable("id") String id, @RequestBody CreateGrant body) {
        return response(chat.grant(RequestIdentity.caller(request), id, key(request), version(request), body));
    }
    @DeleteMapping("/grants/{grantId}")
    ResponseEntity<View> revoke(HttpServletRequest request, @PathVariable("id") String id, @PathVariable("grantId") String grant) {
        return response(chat.revokeGrant(RequestIdentity.caller(request), id, grant, key(request), version(request)));
    }
}
