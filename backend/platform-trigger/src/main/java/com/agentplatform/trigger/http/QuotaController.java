package com.agentplatform.trigger.http;

import com.agentplatform.api.chat.ChatApi;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("chat-local") @RequestMapping("/api/v1")
public final class QuotaController {
    private final ChatApi chat;
    public QuotaController(ChatApi chat){this.chat=chat;}
    @GetMapping("/me/usage-quota")
    Object quota(HttpServletRequest request){return ResponseEntity.ok().header("Cache-Control","no-store").body(chat.quota(RequestIdentity.caller(request)));}
}
