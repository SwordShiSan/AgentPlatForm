package com.agentplatform.trigger.http;

import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.domain.identity.IdentityFailure;
import jakarta.servlet.http.HttpServletRequest;

/** Request-local projection populated by the Security filter; never a durable authorization cache. */
public final class RequestIdentity {
    private static final String CALLER = RequestIdentity.class.getName() + ".caller";
    private RequestIdentity() {}
    public static String token(HttpServletRequest request) {
        if (request.getCookies() != null) for (var cookie : request.getCookies()) {
            if ("ap_session".equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }
    static void attach(HttpServletRequest request, ChatApi.Caller caller) { request.setAttribute(CALLER, caller); }
    public static ChatApi.Caller caller(HttpServletRequest request) {
        var value = request.getAttribute(CALLER);
        if (!(value instanceof ChatApi.Caller caller)) throw new IdentityFailure(401, "AUTHENTICATION_REQUIRED");
        return caller;
    }
}
