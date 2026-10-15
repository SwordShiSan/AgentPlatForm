package com.agentplatform.api.identity;

import java.util.List;

public interface IdentityApi {
    record LoginRequest(String username, String password) {}
    record CsrfResponse(String csrfToken) {}
    record SpaceResponse(String id, String type, String role, long objectLimitBytes) {}
    record MeResponse(String id, String username, boolean platformAdmin, List<SpaceResponse> spaces) {}
    record SessionResult(String cookieToken, String csrfToken) {}
    SessionResult csrf(String token);
    SessionResult login(String token, String csrf, String origin, String ip, LoginRequest request);
    MeResponse me(String token, String selectedSpace);
    void logout(String token, String csrf, String origin);
}
