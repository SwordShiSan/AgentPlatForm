package com.agentplatform.trigger.http;

import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.api.identity.IdentityApi;
import com.agentplatform.domain.chat.ChatFailure;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

@RestController @Profile("chat-local") @RequestMapping("/api/v1")
public final class ChatController {
    private final ChatApi chat;private final IdentityApi identity;private static final JsonMapper JSON=JsonMapper.builder().build();
    public ChatController(ChatApi chat,IdentityApi identity){this.chat=chat;this.identity=identity;}
    private String token(HttpServletRequest request){if(request.getCookies()!=null)for(var cookie:request.getCookies())if("ap_session".equals(cookie.getName()))return cookie.getValue();return null;}
    private ChatApi.Caller caller(HttpServletRequest request,boolean write){
        String token=token(request),space=request.getHeader("X-Space-Id");if(write)identity.verifyWrite(token,request.getHeader("X-CSRF-Token"),request.getHeader("Origin"));
        var me=identity.me(token,space);if(space==null)space=me.spaces().stream().filter(s->s.type().equals("PERSONAL")).findFirst().orElseThrow(()->new ChatFailure(404,"RESOURCE_NOT_FOUND")).id();return new ChatApi.Caller(me.id(),space);
    }
    private static String key(HttpServletRequest request){String value=request.getHeader("Idempotency-Key");if(value==null)throw new ChatFailure(428,"IDEMPOTENCY_KEY_REQUIRED");return value;}
    private static void fields(Map<String,?> body,String...allowed){if(!Set.of(allowed).containsAll(body.keySet()))throw new ChatFailure(400,"UNKNOWN_REQUEST_FIELD");}
    @GetMapping("/models") Object models(HttpServletRequest request){return chat.models(caller(request,false));}
    @GetMapping("/conversations") Object conversations(HttpServletRequest request,@RequestParam(name="cursor",required=false)String cursor,@RequestParam(name="limit",defaultValue="20")int limit){return chat.conversations(caller(request,false),cursor,limit);}
    @PostMapping("/conversations") ResponseEntity<ChatApi.ConversationView> create(HttpServletRequest request,@RequestBody Map<String,Object> body){
        var who=caller(request,true);fields(body,"title","workspaceId","initialTask");if(body.get("workspaceId")!=null)throw new ChatFailure(422,"WORKSPACE_NOT_AVAILABLE");Object title=body.get("title");if(title!=null&&!(title instanceof String))throw new ChatFailure(400,"INVALID_TITLE");
        if(body.containsKey("initialTask")){if(!(body.get("initialTask") instanceof Map<?,?> initial))throw new ChatFailure(400,"INVALID_TASK_REQUEST");var input=parseTask(initial);return ResponseEntity.status(201).body(chat.first(who,key(request),(String)title,input.model(),input.source(),input.text()));}
        return ResponseEntity.status(201).body(chat.create(who,key(request),(String)title));
    }
    private record Input(String model,String source,String text) {}
    @SuppressWarnings("unchecked") private static Input parseTask(Map<?,?> raw){
        Map<String,Object> body=(Map<String,Object>)raw;fields(body,"input","options");if(!(body.get("input") instanceof Map<?,?> i)||!(body.get("options") instanceof Map<?,?> o))throw new ChatFailure(400,"INVALID_TASK_REQUEST");
        var input=(Map<String,Object>)i;var options=(Map<String,Object>)o;fields(input,"text","attachments");fields(options,"modelId","credentialSource","planMode","permissionMode");
        if(!(input.get("text") instanceof String text)|| (options.get("modelId")!=null&&!(options.get("modelId") instanceof String))||(options.get("credentialSource")!=null&&!(options.get("credentialSource") instanceof String)))throw new ChatFailure(400,"INVALID_TASK_REQUEST");
        if((input.get("attachments")!=null&&(!(input.get("attachments") instanceof List<?> attachments)||!attachments.isEmpty()))||Boolean.TRUE.equals(options.get("planMode"))||(options.get("permissionMode")!=null&&!"RISK_BASED".equals(options.get("permissionMode"))))throw new ChatFailure(422,"CAPABILITY_NOT_AVAILABLE");
        if(options.get("planMode")!=null&&!(options.get("planMode") instanceof Boolean))throw new ChatFailure(400,"INVALID_TASK_REQUEST");
        return new Input((String)options.get("modelId"),(String)options.get("credentialSource"),text);
    }
    private static long version(HttpServletRequest request){String value=request.getHeader("If-Match");if(value==null)throw new ChatFailure(428,"VERSION_REQUIRED");try{if(value.length()<3||value.charAt(0)!=34||value.charAt(value.length()-1)!=34)throw new NumberFormatException();String number=value.substring(1,value.length()-1);if(!number.chars().allMatch(Character::isDigit))throw new NumberFormatException();return Long.parseLong(number);}catch(Exception invalid){throw new ChatFailure(400,"INVALID_VERSION");}}
    private static ResponseEntity<Map<String,Object>> versioned(Map<String,Object> body){return ResponseEntity.ok().eTag("\""+body.get("version")+"\"").header("Cache-Control","no-store").body(body);}
    @GetMapping("/me/preferences") Object preferences(HttpServletRequest request){return versioned(chat.preferences(caller(request,false)));}
    @PatchMapping("/me/preferences") Object preferences(HttpServletRequest request,@RequestBody Map<String,Object> body){return versioned(chat.preferences(caller(request,true),version(request),body));}
    @GetMapping("/effective-config") Object effective(HttpServletRequest request){return chat.effective(caller(request,false));}
    @GetMapping("/tasks/{id}/configuration") Object configuration(HttpServletRequest request,@PathVariable("id")String id){return chat.configuration(caller(request,false),id);}
    @GetMapping("/me/model-credentials") Object credentials(HttpServletRequest request){return versioned(chat.credentialMetadata(caller(request,false)));}
    @PutMapping("/me/model-credentials/deepseek") Object credentials(HttpServletRequest request,@RequestBody Map<String,Object> body){var who=caller(request,true);fields(body,"apiKey");if(!(body.get("apiKey") instanceof String secret))throw new ChatFailure(400,"INVALID_CREDENTIAL");return versioned(chat.replaceCredential(who,version(request),secret));}
    @DeleteMapping("/me/model-credentials/deepseek") Object revokeCredential(HttpServletRequest request){return versioned(chat.replaceCredential(caller(request,true),version(request),null));}
    @GetMapping("/conversations/{id}") Object conversation(HttpServletRequest request,@PathVariable("id")String id){return chat.conversation(caller(request,false),id);}
    @GetMapping("/conversations/{id}/snapshot") Object snapshot(HttpServletRequest request,@PathVariable("id")String id){return chat.snapshot(caller(request,false),id);}
    @PostMapping("/conversations/{id}/tasks") ResponseEntity<ChatApi.Accepted> submit(HttpServletRequest request,@PathVariable("id")String id,@RequestBody Map<String,Object> body){var who=caller(request,true);var input=parseTask(body);return ResponseEntity.accepted().body(chat.submitConfigured(who,id,key(request),input.model(),input.source(),input.text()));}
    @GetMapping("/tasks/{id}") Object task(HttpServletRequest request,@PathVariable("id")String id){return chat.task(caller(request,false),id);}
    @GetMapping("/tasks/{id}/usage") Object usage(HttpServletRequest request,@PathVariable("id")String id){return Map.of("items",chat.usage(caller(request,false),id));}
    @PostMapping("/tasks/{id}/cancel") ResponseEntity<ChatApi.TaskView> cancel(HttpServletRequest request,@PathVariable("id")String id,@RequestBody(required=false)Map<String,Object> body){var who=caller(request,true);if(body!=null)fields(body);return ResponseEntity.accepted().body(chat.cancel(who,id,key(request)));}
    @GetMapping(value="/conversations/{id}/events",produces="text/event-stream")
    ResponseEntity<StreamingResponseBody> events(HttpServletRequest request,HttpServletResponse response,@PathVariable("id")String id,@RequestParam(name="after",required=false)Long after){
        var who=caller(request,false);String cookie=token(request),selected=request.getHeader("X-Space-Id");long initial;
        try{initial=after!=null?after:request.getHeader("Last-Event-ID")==null?0:Long.parseLong(request.getHeader("Last-Event-ID"));}catch(NumberFormatException e){throw new ChatFailure(400,"INVALID_CURSOR");}
        chat.events(who,id,initial); // reject before starting a 200 stream
        StreamingResponseBody body=output->{
            long cursor=initial,end=System.nanoTime()+25_000_000_000L,heartbeat=System.nanoTime();
            output.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));output.flush();response.flushBuffer();
            try{while(System.nanoTime()<end){identity.me(cookie,selected);var events=chat.events(who,id,cursor);int bytes=0;
                for(var event:events){byte[] wire=("id: "+event.seq()+"\nevent: platform_event\ndata: "+JSON.writeValueAsString(event)+"\n\n").getBytes(StandardCharsets.UTF_8);bytes+=wire.length;if(bytes>1048576)return;output.write(wire);cursor=event.seq();}if(!events.isEmpty()){output.flush();response.flushBuffer();}
                if(System.nanoTime()-heartbeat>2_000_000_000L){output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));output.flush();response.flushBuffer();heartbeat=System.nanoTime();}Thread.sleep(250);
            }}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(com.agentplatform.domain.identity.IdentityFailure|ChatFailure revoked){/* Close without leaking internal error text; reconnect reauthorizes. */}
        };
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.TEXT_EVENT_STREAM).header("Cache-Control","no-store").header("X-Accel-Buffering","no").body(body);
    }
}
