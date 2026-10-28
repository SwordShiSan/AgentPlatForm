package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.workspace.EnvironmentUseData;
import com.agentplatform.domain.workspace.WorkspaceShellData;
import com.agentplatform.domain.workspace.WorkspaceFailure;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/** Framework type resolution is selected by the trusted route/stored kind, not client class names. */
public final class EnvironmentUseCodecs {
    private static final JsonMapper LEGACY=mapper(EnvironmentUseData.Request.class,"workspace-use-v1");
    private static final JsonMapper SHELL=mapper(WorkspaceShellData.Request.class,"workspace-shell-v1");
    private EnvironmentUseCodecs() {}
    private static JsonMapper mapper(Class<? extends EnvironmentUseData.UseRequest> type,String name) {
        var module=new SimpleModule(name).addAbstractTypeMapping(EnvironmentUseData.UseRequest.class,type);
        return JsonMapper.builder().addModule(module).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }
    public static JsonMapper legacy(){return LEGACY;}
    public static JsonMapper shell(){return SHELL;}
    public static EnvironmentUseData.UseRequest stored(String kind,String text) {
        EnvironmentUseData.UseRequest request="SHELL".equals(kind)?SHELL.readValue(text,WorkspaceShellData.Request.class):LEGACY.readValue(text,EnvironmentUseData.Request.class);
        if(!request.kind().equals(kind))throw new WorkspaceFailure(409,"ENVIRONMENT_USE_REQUEST_KIND_MISMATCH");
        return request;
    }
}
