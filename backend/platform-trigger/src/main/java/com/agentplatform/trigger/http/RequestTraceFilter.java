package com.agentplatform.trigger.http;

import com.agentplatform.types.RequestTrace;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(-200)
public final class RequestTraceFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        UUID trace=UUID.randomUUID();RequestTrace.enter(trace);response.setHeader("X-Trace-Id",trace.toString());
        try { chain.doFilter(request,response); } finally { RequestTrace.clear(); }
    }
}
