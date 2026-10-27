package com.agentplatform.infrastructure.harness.agentscope;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ExactApprovalProbeTest {
    @TempDir Path root;
    @ParameterizedTest @ValueSource(strings = {"allow", "deny"})
    void actualSdkRestoresOriginalCallAndPersistsExactDecisionAcrossJvmExit(String decision) throws Exception {
        Path fixture = root.resolve(decision); Files.createDirectories(fixture);
        process(fixture, "start"); process(fixture, decision);
        assertNotEquals(Files.readString(fixture.resolve("start.done")), Files.readString(fixture.resolve(decision + ".done")));
    }
    private void process(Path fixture, String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Path log = fixture.resolve(mode + ".log");
        var child = new ProcessBuilder(java, "-Xmx160m", "-cp", System.getProperty("java.class.path"), ExactApprovalProbeProcess.class.getName(), fixture.toString(), mode)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!child.waitFor(35, TimeUnit.SECONDS)) { child.destroyForcibly(); fail("SDK experiment timed out"); }
        assertEquals(0, child.exitValue(), () -> { try { return Files.readString(log); } catch (Exception e) { return e.toString(); } });
    }
}
