package com.agentplatform.types;

import java.util.UUID;

public final class WorkbenchPreferences {
    private WorkbenchPreferences() {}
    public record Layout(String view,int sidebarWidth) {
        public Layout { if(!java.util.Set.of("SPLIT","CHAT","FILES").contains(view)||sidebarWidth<240||sidebarWidth>640)throw new IllegalArgumentException("Invalid workbench layout"); }
    }
    public record RecentFile(String workspaceId,String fileId,String versionId) {
        public RecentFile {UUID.fromString(workspaceId);UUID.fromString(fileId);UUID.fromString(versionId);}
    }
}
