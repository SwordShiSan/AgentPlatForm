package com.agentplatform.app.workspace;

import java.nio.file.*;
import java.util.Set;

/** Existing host-driven acceptance pattern; never controlled by a user/API command. */
final class WorkspaceFixtureBarrier {
    private static final Set<String> POINTS=Set.of("claim-before-commit","claim-after-commit-before-ack","ack-before-runner","helper-exited-before-receipt");
    private final String selected;
    private WorkspaceFixtureBarrier(String selected){this.selected=selected;}
    static WorkspaceFixtureBarrier none(){return new WorkspaceFixtureBarrier(null);}
    static WorkspaceFixtureBarrier configured(String selected,String stage,boolean fixtureProfile,boolean productionProfile) {
        if(selected==null||selected.isBlank())return none();
        if(!POINTS.contains(selected)||!"r31".equals(stage)||!fixtureProfile||productionProfile)
            throw new IllegalStateException("Workspace fault barrier requires explicit isolated R31 fixture composition");
        return new WorkspaceFixtureBarrier(selected);
    }
    void await(String point) {
        if(!point.equals(selected))return;
        try {
            Files.writeString(Path.of("/tmp/workspace-fixture-ready"),point,StandardOpenOption.CREATE_NEW);
            while(true)Thread.sleep(250);
        }catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IllegalStateException("WORKSPACE_FIXTURE_INTERRUPTED");}
        catch(java.io.IOException unavailable){throw new IllegalStateException("WORKSPACE_FIXTURE_MARKER_UNAVAILABLE");}
    }
}
