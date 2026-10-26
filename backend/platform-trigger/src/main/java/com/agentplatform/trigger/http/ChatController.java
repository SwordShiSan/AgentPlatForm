package com.agentplatform.trigger.http;

import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.api.identity.IdentityApi;
import com.agentplatform.domain.chat.ChatFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("chat-local") @RequestMapping("/api/v1")
public final class ChatController {
    @org.springframework.beans.factory.annotation.Value("${platform.chat.plan-enabled:false}") private boolean planEnabled;
    @org.springframework.beans.factory.annotation.Value("${platform.chat.permissions-enabled:false}") private boolean permissionsEnabled;
    private final ChatApi chat;private final IdentityApi identity;private final com.agentplatform.api.chat.ChatEventStream streams;
    public ChatController(ChatApi chat,IdentityApi identity,com.agentplatform.api.chat.ChatEventStream streams){this.chat=chat;this.identity=identity;this.streams=streams;}
    private ChatApi.Caller caller(HttpServletRequest request,boolean write) { return RequestIdentity.caller(request); }
    private static String key(HttpServletRequest request){String value=request.getHeader("Idempotency-Key");if(value==null)throw new ChatFailure(428,"IDEMPOTENCY_KEY_REQUIRED");return value;}
    @GetMapping("/models") Object models(HttpServletRequest request){return chat.models(caller(request,false));}
    @GetMapping("/tasks/{id}/recovery") Object recovery(HttpServletRequest request,@PathVariable("id")String id){return chat.recovery(caller(request,false),id);}
    @GetMapping("/conversations") Object conversations(HttpServletRequest request,@RequestParam(name="cursor",required=false)String cursor,@RequestParam(name="limit",defaultValue="20")int limit){return chat.conversations(caller(request,false),cursor,limit);}
    @PostMapping("/conversations") ResponseEntity<ChatApi.ConversationView> create(HttpServletRequest request,@jakarta.validation.Valid @RequestBody ChatRequests.Conversation body) {
        var who=caller(request,true);
        if(body.workspaceId()!=null)throw new ChatFailure(422,"WORKSPACE_NOT_AVAILABLE");
        if(body.initialTask()!=null){var input=parseTask(body.initialTask());return ResponseEntity.status(201).body(chat.firstAuthorized(who,key(request),body.title(),input.model(),input.source(),input.text(),input.plan(),input.workspace(),input.mode()));}
        return ResponseEntity.status(201).body(chat.create(who,key(request),body.title()));
    }
    private record Input(String model,String source,String text,boolean plan,String workspace,com.agentplatform.types.TaskPermissionData.Mode mode) {}
    private Input parseTask(ChatRequests.Task body) {
        var input=body.input();var options=body.options();
        if((input.attachments()!=null&&!input.attachments().isEmpty())||(Boolean.TRUE.equals(options.planMode())&&!planEnabled)||(!permissionsEnabled&&(options.workspaceId()!=null||"FULL".equals(options.permissionMode()))))throw new ChatFailure(422,"CAPABILITY_NOT_AVAILABLE");
        com.agentplatform.types.TaskPermissionData.Mode mode;
        try{mode=options.permissionMode()==null?com.agentplatform.types.TaskPermissionData.Mode.RISK_BASED:com.agentplatform.types.TaskPermissionData.Mode.valueOf(options.permissionMode());}
        catch(IllegalArgumentException invalid){throw new ChatFailure(400,"INVALID_PERMISSION_MODE");}
        return new Input(options.modelId(),options.credentialSource(),input.text(),Boolean.TRUE.equals(options.planMode()),options.workspaceId(),mode);
    }
    private static long version(HttpServletRequest request){String value=request.getHeader("If-Match");if(value==null)throw new ChatFailure(428,"VERSION_REQUIRED");try{if(value.length()<3||value.charAt(0)!=34||value.charAt(value.length()-1)!=34)throw new NumberFormatException();String number=value.substring(1,value.length()-1);if(!number.chars().allMatch(Character::isDigit))throw new NumberFormatException();return Long.parseLong(number);}catch(Exception invalid){throw new ChatFailure(400,"INVALID_VERSION");}}
    private static ResponseEntity<Map<String,Object>> versioned(Map<String,Object> body){return ResponseEntity.ok().eTag("\""+body.get("version")+"\"").header("Cache-Control","no-store").body(body);}
    @GetMapping("/me/preferences") Object preferences(HttpServletRequest request){return versioned(chat.preferences(caller(request,false)));}
    @PatchMapping("/me/preferences") Object preferences(HttpServletRequest request,@RequestBody ChatRequests.PreferencePatch body){return versioned(chat.preferences(caller(request,true),version(request),body.values()));}
    @GetMapping("/effective-config") Object effective(HttpServletRequest request){return chat.effective(caller(request,false));}
    @GetMapping("/tasks/{id}/configuration") Object configuration(HttpServletRequest request,@PathVariable("id")String id){return chat.configuration(caller(request,false),id);}
    @GetMapping("/me/model-credentials") Object credentials(HttpServletRequest request){return versioned(chat.credentialMetadata(caller(request,false)));}
    @PutMapping("/me/model-credentials/deepseek") Object credentials(HttpServletRequest request,@jakarta.validation.Valid @RequestBody ChatRequests.Credential body){return versioned(chat.replaceCredential(caller(request,true),version(request),body.apiKey()));}
    @DeleteMapping("/me/model-credentials/deepseek") Object revokeCredential(HttpServletRequest request){return versioned(chat.replaceCredential(caller(request,true),version(request),null));}
    @GetMapping("/conversations/{id}") Object conversation(HttpServletRequest request,@PathVariable("id")String id){return chat.conversation(caller(request,false),id);}
    @GetMapping("/conversations/{id}/snapshot") Object snapshot(HttpServletRequest request,@PathVariable("id")String id){return chat.snapshot(caller(request,false),id);}
    @PostMapping("/conversations/{id}/tasks") ResponseEntity<ChatApi.Accepted> submit(HttpServletRequest request,@PathVariable("id")String id,@jakarta.validation.Valid @RequestBody ChatRequests.Task body){var who=caller(request,true);var input=parseTask(body);return ResponseEntity.accepted().body(chat.submitAuthorized(who,id,key(request),input.model(),input.source(),input.text(),input.plan(),input.workspace(),input.mode()));}
    @GetMapping("/tasks/{id}") Object task(HttpServletRequest request,@PathVariable("id")String id){return chat.task(caller(request,false),id);}
    @GetMapping("/tasks/{id}/diagnostics") Object diagnostics(HttpServletRequest request,@PathVariable("id")String id){return chat.diagnostics(caller(request,false),id);}
    @GetMapping("/tasks/{id}/reconciliation") ChatApi.ReconciliationView reconciliation(HttpServletRequest request,@PathVariable("id")String id){return chat.reconciliation(caller(request,false),id);}
    @GetMapping("/tasks/{id}/usage") Object usage(HttpServletRequest request,@PathVariable("id")String id){return Map.of("items",chat.usage(caller(request,false),id));}
    @PostMapping("/tasks/{id}/cancel") ResponseEntity<ChatApi.TaskView> cancel(HttpServletRequest request,@PathVariable("id")String id,@RequestBody(required=false)ChatRequests.Empty body){var who=caller(request,true);return ResponseEntity.accepted().body(chat.cancel(who,id,key(request)));}
    @GetMapping(value="/conversations/{id}/events", produces="text/event-stream")
    ResponseEntity<reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<ChatApi.EventView>>> events(
            HttpServletRequest request, @PathVariable("id") String id, @RequestParam(name="after",required=false) String after) {
        long cursor;
        try {
            String raw = after != null ? after : request.getHeader("Last-Event-ID");
            if (raw != null && !raw.matches("-?[0-9]+")) throw new NumberFormatException();
            cursor = raw == null ? 0 : Long.parseLong(raw);
            if (cursor > 9007199254740991L) throw new NumberFormatException();
        } catch (NumberFormatException failure) { throw new ChatFailure(400, "INVALID_CURSOR"); }
        var who = caller(request, false);
        String cookie = RequestIdentity.token(request), selected = request.getHeader("X-Space-Id");
        var handle = streams.open(who, id, cursor, () -> identity.me(cookie, selected));
        request.setAttribute(com.agentplatform.api.chat.ChatEventStream.Handle.class.getName(), handle);
        var body = reactor.adapter.JdkFlowAdapter.flowPublisherToFlux(handle.frames()).map(frame -> {
            var event = org.springframework.http.codec.ServerSentEvent.<ChatApi.EventView>builder();
            if (frame.event() == null) return event.comment(frame.comment()).build();
            return event.id(Long.toString(frame.event().seq())).event("platform_event").data(frame.event()).build();
        });
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-store").header("X-Accel-Buffering", "no").body(body);
    }
}
