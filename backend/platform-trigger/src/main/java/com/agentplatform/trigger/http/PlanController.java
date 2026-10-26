package com.agentplatform.trigger.http;

import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.types.PlanInteractionData.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("chat-local") @RequestMapping("/api/v1")
@ConditionalOnProperty(name="platform.chat.plan-enabled",havingValue="true")
public final class PlanController {
    private final ChatApi chat;
    public PlanController(ChatApi chat){this.chat=chat;}
    @GetMapping("/tasks/{id}/plan")
    Object plan(HttpServletRequest request,@PathVariable("id") String id){return versioned(chat.plan(RequestIdentity.caller(request),id));}
    @PutMapping("/tasks/{id}/plan")
    Object edit(HttpServletRequest request,@PathVariable("id") String id,@RequestBody Edit body){return versioned(chat.editPlan(RequestIdentity.caller(request),id,version(request),body));}
    @PostMapping("/tasks/{id}/interactions/{interactionId}/answer")
    Object answer(HttpServletRequest request,@PathVariable("id") String id,@PathVariable("interactionId") String interaction,@RequestBody Answer body){
        String key=request.getHeader("Idempotency-Key");if(key==null)throw new ChatFailure(428,"IDEMPOTENCY_KEY_REQUIRED");
        return ResponseEntity.accepted().header("Cache-Control","no-store").body(chat.answer(RequestIdentity.caller(request),id,interaction,key,body));
    }
    static long version(HttpServletRequest request){
        String value=request.getHeader("If-Match");if(value==null)throw new ChatFailure(428,"VERSION_REQUIRED");
        if(!value.matches("\"(0|[1-9][0-9]*)\""))throw new ChatFailure(400,"INVALID_VERSION");
        try{return Long.parseLong(value.substring(1,value.length()-1));}catch(NumberFormatException invalid){throw new ChatFailure(400,"INVALID_VERSION");}
    }
    private static ResponseEntity<Plan> versioned(Plan plan){return ResponseEntity.ok().eTag("\""+plan.version()+"\"").header("Cache-Control","no-store").body(plan);}
}
