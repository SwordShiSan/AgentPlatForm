package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.infrastructure.dao.chat.ChatMappers;
import com.agentplatform.types.harness.HarnessData.Bundle;
import java.util.UUID;

/** Version resolution is task-scoped: deploying R4.4 does not rewrite old bundle identities. */
final class WorkspaceToolPersistence extends ChatPersistenceSupport {
    static final UUID BUNDLE=UUID.fromString("0199e000-0000-7000-8000-000000000020");
    WorkspaceToolPersistence(ChatMappers mappers){super(mappers);}
    static boolean enabled(ChatMappers c,UUID space,UUID task) {
        var catalog=c.workspaceTools().catalog(space,task);
        if(catalog==null)return false;
        if(!WorkspaceTools.VERSION.equals(catalog.catalogVersion())||!BUNDLE.equals(catalog.runtimeBundleId()))
            throw new ChatFailure(409,"TOOL_CATALOG_INCOMPATIBLE");
        return true;
    }
    static Bundle bundle(ChatMappers c,UUID space,UUID task){return enabled(c,space,task)?ChatRuntime.workspaceBundle():ChatRuntime.bundle();}
    static UUID bundleId(ChatMappers c,UUID space,UUID task){return enabled(c,space,task)?BUNDLE:ChatPersistenceSupport.BUNDLE;}
}
