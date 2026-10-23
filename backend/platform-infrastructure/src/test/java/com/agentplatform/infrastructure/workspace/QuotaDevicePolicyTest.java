package com.agentplatform.infrastructure.workspace;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class QuotaDevicePolicyTest {
    @Test void exactLoopNodeGetsReadPermissionOnly() {
        var device=DockerWorkspaceAllocator.fixedQuotaDevice("/dev/loop17");
        assertEquals("r",device.getcGroupPermissions());
        assertEquals("/dev/loop17",device.getPathOnHost());
        assertEquals("/dev/loop17",device.getPathInContainer());
    }
    @Test void arbitraryDevicesMappingsAndPathsAreRefused() {
        for(String path:new String[]{"/dev/sda","/dev/loop1:/dev/loop2:rwm","/dev/loop-1","/dev/loop1/../sda","/dev/loop123456"," /dev/loop1","/dev/loop1 ","*"})
            assertThrows(IllegalArgumentException.class,()->DockerWorkspaceAllocator.fixedQuotaDevice(path),path);
    }
    @Test void emptyDesktopReferenceNeverAddsAnotherDevice() {
        assertNull(DockerWorkspaceAllocator.fixedQuotaDevice(null));
        assertNull(DockerWorkspaceAllocator.fixedQuotaDevice(""));
    }
    @Test void storageAndSandboxAcceptTheSameApprovedR32Root() {
        for(String root:new String[]{"/srv/agentplatform/workspaces","/srv/agentplatform/acceptance/r31-old-fixture/workspaces",
                "/srv/agentplatform/acceptance/r32-local-242c33332861/workspaces"})assertEquals(root,DockerWorkspaceAllocator.fixedWorkspaceRoot(root));
        for(String root:new String[]{"/","/home/user/workspaces","/srv/agentplatform/acceptance/r33-future/workspaces",
                "/srv/agentplatform/acceptance/r32-fixture/../other/workspaces","/srv/agentplatform/acceptance/r32-fixture_1/workspaces",
                "/srv/agentplatform/acceptance/r32-FIXTURE/workspaces","/srv/agentplatform/acceptance/r32-fixture/workspaces/","/srv/agentplatform/workspaces/../secrets"})
            assertThrows(IllegalArgumentException.class,()->DockerWorkspaceAllocator.fixedWorkspaceRoot(root));
        assertThrows(IllegalArgumentException.class,()->DockerWorkspaceAllocator.fixedWorkspaceRoot(null));
    }
}
