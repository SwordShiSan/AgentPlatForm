package com.agentplatform.infrastructure.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DockerSandboxRuntimeCapabilityFailureTest {
    @Test void classifiesOnlyAllowListedGuestFailureSignals() {
        assertEquals("CAPABILITY_EXEC_EXIT_UNCONFIRMED",
                DockerSandboxRuntime.capabilityFailureCode(null, null, false, ""));
        assertEquals("CAPABILITY_EXEC_STREAM_FAILED",
                DockerSandboxRuntime.capabilityFailureCode(1L, new IllegalStateException("private"), false, ""));
        assertEquals("CAPABILITY_EXEC_OUTPUT_LIMIT",
                DockerSandboxRuntime.capabilityFailureCode(1L, null, true, "FILE_HASH"));
        assertEquals("CAPABILITY_FILE_HASH_MISMATCH",
                DockerSandboxRuntime.capabilityFailureCode(1L, null, false, "FILE_HASH: /private/path"));
        assertEquals("CAPABILITY_INPUT_TRUNCATED",
                DockerSandboxRuntime.capabilityFailureCode(1L, null, false, "INPUT_LENGTH"));
        assertEquals("CAPABILITY_MOUNT_WRITABLE",
                DockerSandboxRuntime.capabilityFailureCode(1L, null, false, "MOUNT_WRITABLE: /private/path"));
        assertEquals("CAPABILITY_PERMISSION_DENIED",
                DockerSandboxRuntime.capabilityFailureCode(1L, null, false, "PermissionError: secret path"));
        assertEquals("CAPABILITY_GUEST_PROCESS_FAILED",
                DockerSandboxRuntime.capabilityFailureCode(1L, null, false, "secret text /private/path"));
        assertEquals("CAPABILITY_EXEC_RESULT_INVALID",
                DockerSandboxRuntime.capabilityFailureCode(0L, null, false, ""));
    }
}
