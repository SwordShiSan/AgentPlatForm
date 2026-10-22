package com.agentplatform.app.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.infrastructure.workspace.WorkspaceRunnerStopProof;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Linux offline operator only. Arguments contain file paths, never key material. */
@Component @Profile("workspace-environment-ops")
@ConditionalOnProperty(name="platform.workspace.environment.ops-enabled",havingValue="true")
public final class RunnerProofOperator implements ApplicationRunner {
    private final RunnerStopProofRepository repository;
    public RunnerProofOperator(RunnerStopProofRepository repository) {this.repository=repository;}
    private static String option(ApplicationArguments arguments,String name) {
        var values=arguments.getOptionValues(name);
        if(values==null||values.size()!=1)throw new WorkspaceFailure(400,"MISSING_OPERATOR_OPTION");
        return values.getFirst();
    }
    private static byte[] privateFile(Path file,long maximum) {
        try {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>maximum)throw new IllegalArgumentException();
            var forbidden=Set.of(PosixFilePermission.GROUP_READ,PosixFilePermission.GROUP_WRITE,PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,PosixFilePermission.OTHERS_WRITE,PosixFilePermission.OTHERS_EXECUTE);
            if(Files.getPosixFilePermissions(file,LinkOption.NOFOLLOW_LINKS).stream().anyMatch(forbidden::contains))throw new IllegalArgumentException();
            return Files.readAllBytes(file);
        } catch(Exception rejected) {throw new WorkspaceFailure(400,"OPERATOR_FILE_NOT_PRIVATE");}
    }
    @Override public void run(ApplicationArguments arguments) {
        if(!"import-runner-proof".equals(option(arguments,"workspace-environment-action")))throw new WorkspaceFailure(400,"INVALID_OPERATOR_ACTION");
        byte[] proof=privateFile(Path.of(option(arguments,"proof-file")),8192);
        byte[] key=privateFile(Path.of(option(arguments,"host-key-file")),32);
        try {
            var verified=WorkspaceRunnerStopProof.verify(proof,key,Clock.systemUTC());repository.record(verified);
            System.out.println("Runner host proof recorded: "+verified.instance().id()+" "+verified.evidenceHash());
        } finally {Arrays.fill(key,(byte)0);}
    }
}
