package com.agentplatform.api.capability;

import com.agentplatform.api.chat.ChatApi;
import java.util.List;

/** The secret is accepted only as a method argument and is never included in a response. */
public interface ConnectorCredentialApi {
    record Metadata(String id, long revision, String state, String createdAt) {}
    record Page(List<Metadata> items, String nextCursor) {}
    Page list(ChatApi.Caller caller, String cursor, int limit);
    Metadata create(ChatApi.Caller caller, String requestId, String secret);
    Metadata replace(ChatApi.Caller caller, String id, long expected, String requestId, String secret);
    Metadata revoke(ChatApi.Caller caller, String id, long expected, String requestId);
}
