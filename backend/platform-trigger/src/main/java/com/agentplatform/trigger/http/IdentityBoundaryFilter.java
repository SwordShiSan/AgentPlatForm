package com.agentplatform.trigger.http;

import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.api.identity.IdentityApi;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.identity.IdentityFailure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

final class IdentityBoundaryFilter extends OncePerRequestFilter {
    static final Set<String> PUBLIC = Set.of("/api/v1/health", "/api/v1/readiness", "/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/auth/logout");
    private final IdentityApi identity;
    private final HandlerExceptionResolver errors;
    IdentityBoundaryFilter(IdentityApi identity, HandlerExceptionResolver errors) { this.identity = identity; this.errors = errors; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/") || path.equals("/api/v1/health") || path.equals("/api/v1/readiness");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        try {
            String path = request.getRequestURI(), token = RequestIdentity.token(request);
            if (path.equals("/api/v1/auth/csrf") && "cross-site".equals(request.getHeader("Sec-Fetch-Site"))) throw new IdentityFailure(403, "ORIGIN_REJECTED");
            if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
                identity.verifyWrite(token, request.getHeader("X-CSRF-Token"), request.getHeader("Origin"));
            }
            if (!PUBLIC.contains(path)) {
                String selected = request.getHeader("X-Space-Id");
                var me = identity.me(token, selected);
                String space = selected == null ? me.spaces().stream().filter(s -> "PERSONAL".equals(s.type())).findFirst()
                        .orElseThrow(() -> new ChatFailure(404, "RESOURCE_NOT_FOUND")).id() : selected;
                RequestIdentity.attach(request, new ChatApi.Caller(me.id(), space));
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(me.id(), null, List.of()));
                SecurityContextHolder.setContext(context);
            }
            chain.doFilter(request, response);
        } catch (IdentityFailure | ChatFailure failure) {
            if (response.isCommitted()) throw failure;
            errors.resolveException(request, response, null, failure);
        } finally { SecurityContextHolder.clearContext(); }
    }
}
