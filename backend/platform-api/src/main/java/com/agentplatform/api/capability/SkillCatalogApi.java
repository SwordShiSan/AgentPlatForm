package com.agentplatform.api.capability;

import com.agentplatform.api.chat.ChatApi;
import java.util.List;

public interface SkillCatalogApi {
    record Item(String capabilityId, String versionId, long versionNumber, String name, String description, String state, long revision) {}
    record Page(List<Item> items, String nextCursor) {}
    Page skills(ChatApi.Caller caller, String cursor, int limit);
    Page versions(ChatApi.Caller caller, String capabilityId, String cursor, int limit);
}
