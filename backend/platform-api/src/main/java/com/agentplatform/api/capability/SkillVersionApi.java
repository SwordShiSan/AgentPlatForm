package com.agentplatform.api.capability;

import com.agentplatform.api.chat.ChatApi;
import java.util.List;

public interface SkillVersionApi {
    record Resource(String path, long bytes, String sha256) {}
    record View(String capabilityId, String versionId, String state, long revision, String name, String description,
                List<Resource> resources, List<Object> dependencies, boolean assetAvailable) {}
    View get(ChatApi.Caller caller, String capabilityId, String versionId);
    View change(ChatApi.Caller caller, String capabilityId, String versionId, String action, long expected, String requestId);
}
