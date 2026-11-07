package com.agentplatform.api.capability;

import com.agentplatform.api.chat.ChatApi;
import java.util.List;

/** Capability management projections, separate from task runtime configuration. */
public interface CapabilityBindingsApi {
    record Selection(String capabilityId, String versionId) {}
    record Update(boolean inheritPersonal, List<Selection> selections) {}
    record Bindings(long revision, boolean inheritPersonal, List<Selection> selections) {}
    record Effective(String source, List<Selection> selections, List<Selection> unavailable) {}
    Bindings read(ChatApi.Caller caller, String workspaceId);
    Bindings replace(ChatApi.Caller caller, String workspaceId, long expected, Update update, String requestId);
    Effective effective(ChatApi.Caller caller, String workspaceId);
}
