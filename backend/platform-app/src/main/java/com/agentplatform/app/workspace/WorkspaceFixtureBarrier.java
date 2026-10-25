package com.agentplatform.app.workspace;

import java.nio.file.*;
import java.util.Set;

/** Existing host-driven acceptance pattern; never controlled by a user/API command. */
final class WorkspaceFixtureBarrier implements com.agentplatform.domain.workspace.WorkspaceEffectBoundary {
    private static final Set<String> POINTS=Set.of("claim-before-commit","claim-after-commit-before-ack","ack-before-runner","helper-exited-before-receipt");
    private static final Set<String> ENVIRONMENT_POINTS=Set.of("environment-claim-before-commit","environment-claim-after-commit-before-ack",
            "environment-ack-before-runner","environment-runner-accepted","environment-before-create","environment-created-before-start",
            "environment-started-before-readiness","environment-readiness-before-receipt","environment-receipt-before-response",
            "use-output-before-result","use-result-before-response","file-helper-before-exec-start");
    private final String selected;
    private static final Set<String> FILE_POINTS=Set.of("file-claim-after-commit-before-ack","file-ack-before-runner");
    private static final Set<String> LIFECYCLE_POINTS=Set.of("environment-cleanup-before-remove","environment-cleanup-after-remove-before-receipt",
            "environment-cleanup-after-receipt-before-response","environment-observation-before-project","environment-observation-after-project");
    private WorkspaceFixtureBarrier(String selected){this.selected=selected;}
    static WorkspaceFixtureBarrier none(){return new WorkspaceFixtureBarrier(null);}
    static WorkspaceFixtureBarrier configured(String selected,String stage,boolean fixtureProfile,boolean productionProfile) {
        if(selected==null||selected.isBlank())return none();
        boolean allowed="r31".equals(stage)&&POINTS.contains(selected)||"r32".equals(stage)&&(POINTS.contains(selected)||ENVIRONMENT_POINTS.contains(selected))
                ||"r33".equals(stage)&&(POINTS.contains(selected)||ENVIRONMENT_POINTS.contains(selected)||FILE_POINTS.contains(selected))
                ||"r34".equals(stage)&&(POINTS.contains(selected)||ENVIRONMENT_POINTS.contains(selected)||FILE_POINTS.contains(selected)||LIFECYCLE_POINTS.contains(selected));
        if(!allowed||!fixtureProfile||productionProfile)
            throw new IllegalStateException("Workspace fault barrier requires explicit isolated fixture composition");
        return new WorkspaceFixtureBarrier(selected);
    }
    @Override public void await(String point) {
        if(!point.equals(selected))return;
        try {
            Files.writeString(Path.of("/tmp/workspace-fixture-ready"),point,StandardOpenOption.CREATE_NEW);
            while(true)Thread.sleep(250);
        }catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IllegalStateException("WORKSPACE_FIXTURE_INTERRUPTED");}
        catch(java.io.IOException unavailable){throw new IllegalStateException("WORKSPACE_FIXTURE_MARKER_UNAVAILABLE");}
    }
}
