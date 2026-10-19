package com.agentplatform.trigger.http;

import com.agentplatform.api.identity.IdentityApi;
import com.agentplatform.domain.identity.IdentityFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("identity-local")
@RequestMapping("/api/v1")
public final class IdentityController {
    private final IdentityApi identity;
    private final boolean secure;
    public IdentityController(IdentityApi identity, @Value("${platform.identity.secure-cookie:true}") boolean secure) { this.identity = identity; this.secure = secure; }
    private String cookie(String token, long age) {
        return ResponseCookie.from("ap_session", token).httpOnly(true).secure(secure).sameSite("Lax").path("/api/v1").maxAge(Duration.ofSeconds(age)).build().toString();
    }
    @GetMapping("/auth/csrf")
    ResponseEntity<IdentityApi.CsrfResponse> csrf(HttpServletRequest request) {
        if (!"same-origin".equals(request.getHeader("Sec-Fetch-Site")) && "cross-site".equals(request.getHeader("Sec-Fetch-Site"))) throw new IdentityFailure(403, "ORIGIN_REJECTED");
        var result = identity.csrf(RequestIdentity.token(request));
        // Cookie is session-scoped in the browser; database expiry is authoritative.
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(result.cookieToken(), -1)).header(HttpHeaders.CACHE_CONTROL, "no-store").body(new IdentityApi.CsrfResponse(result.csrfToken()));
    }
    @PostMapping("/auth/login")
    ResponseEntity<IdentityApi.CsrfResponse> login(HttpServletRequest request, @jakarta.validation.Valid @RequestBody ChatRequests.Login body) {
        var result = identity.login(RequestIdentity.token(request), request.getHeader("X-CSRF-Token"), request.getHeader("Origin"), request.getRemoteAddr(), new IdentityApi.LoginRequest(body.username(), body.password()));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(result.cookieToken(), -1)).header(HttpHeaders.CACHE_CONTROL, "no-store").body(new IdentityApi.CsrfResponse(result.csrfToken()));
    }
    @GetMapping("/me")
    ResponseEntity<IdentityApi.MeResponse> me(HttpServletRequest request) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(identity.me(RequestIdentity.token(request), request.getHeader("X-Space-Id")));
    }
    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        identity.logout(RequestIdentity.token(request), request.getHeader("X-CSRF-Token"), request.getHeader("Origin"));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0)).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
