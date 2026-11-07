package com.agentplatform.api.capability;

import com.agentplatform.api.chat.ChatApi;
import java.io.InputStream;

public interface SkillImportApi {
    record Create(String capabilityId, long sizeBytes, String sha256) {}
    record View(String id, String capabilityId, String versionId, String state, long sizeBytes, String sha256, long revision) {}
    View create(ChatApi.Caller caller, String requestId, Create create);
    View get(ChatApi.Caller caller, String id);
    View transfer(ChatApi.Caller caller, String id, long length, InputStream stream);
    View complete(ChatApi.Caller caller, String id);
}
