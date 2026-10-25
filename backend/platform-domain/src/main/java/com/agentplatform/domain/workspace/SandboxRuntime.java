package com.agentplatform.domain.workspace;

import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Private data plane; there is deliberately no user-program or host-path argument. */
public interface SandboxRuntime extends AutoCloseable {
    record Identity(UUID environmentId, UUID spaceId, UUID workspaceId, UUID commandId,
                    long generation, long fence, int projectId) {
        public Identity {
            java.util.Objects.requireNonNull(environmentId);
            java.util.Objects.requireNonNull(spaceId);
            java.util.Objects.requireNonNull(workspaceId);
            java.util.Objects.requireNonNull(commandId);
            if (generation < 1 || fence < 1 || projectId < 200001) {
                throw new IllegalArgumentException("Invalid environment identity");
            }
        }
    }
    record Container(String id, String image, String startedAt, boolean running, String policyHash, String readinessHash) {}
    record Output(String execId, long exitCode, String stdout, String stderr,
                  boolean truncated, boolean timedOut, boolean cancelled, Stop stopReceipt) {}
    record Stop(String containerId, String image, String startedAt, String finishedAt,
                boolean execStopped, boolean callbacksStopped, Instant observedAt) {}
    record Removal(String containerId, String image, String startedAt, String finishedAt, Instant observedAt) {}
    enum Probe { PYTHON_666, NODE_CROSS_READ, WAIT_FOR_CANCEL, OUTPUT_BOUND,
        CPU_BOUND, PID_BOUND, MEMORY_BOUND, NETWORK_BOUND, TMP_BOUND, BYTE_QUOTA, INODE_QUOTA }
    enum FileAction { READ, WRITE, LIST }
    record FileRequest(FileAction action, String path, String contentBase64) {}
    @FunctionalInterface interface Authority { void check(); }
    interface UseAuthority extends Authority {
        /** Must persist this exact exec identity before execStart is permitted. */
        void recordExec(String containerId, String execId);
    }
    interface FileHelperAuthority extends Authority {
        FileHelperData.Intent intent();
        FileHelperData.Snapshot snapshot();
        void created(FileHelperData.Physical physical);
        void requestStart();
        void started(FileHelperData.Physical physical);
        void recordExec(FileHelperData.Physical physical);
        void stopped(FileHelperData.Stopped stop);
        FileHelperData.Stopped retirement();
    }
    final class Terminated extends RuntimeException {
        private final Stop receipt;
        public Terminated(Stop receipt, RuntimeException cause) {
            super("Sandbox stopped after authority failure", cause);
            this.receipt = receipt;
        }
        public Stop receipt() { return receipt; }
    }
    final class RejectedBeforeEffect extends RuntimeException {
        public RejectedBeforeEffect(String code) {
            super(code);
            if(code==null||!code.matches("[A-Z_]{1,64}"))throw new IllegalArgumentException("Stable rejection code required");
        }
    }

    Container prepare(Identity identity, Authority authority);
    RunnerInstance runnerInstance();
    Container inspect(Identity identity);
    /** Query original startup facts without re-executing a lost preparation command. */
    Container verifyReady(Identity identity, Authority authority);
    FileHelperData.Intent fileHelperIntent(Identity identity, UUID useId);
    Output file(Identity identity, UUID useId, FileRequest request, FileHelperAuthority authority, BooleanSupplier cancelled);
    Output probe(Identity identity, UUID useId, Probe probe, UseAuthority authority, BooleanSupplier cancelled);
    /** Confirm or query the exact original helper after an interrupted request. Never starts a new helper. */
    FileHelperData.Stopped stopFileHelper(Identity identity, UUID useId, FileHelperAuthority authority);
    /** Remove only after the canonical use fact and immutable stop receipt are committed. */
    void removeStoppedFileHelper(Identity identity, UUID useId, FileHelperData.Intent intent, FileHelperData.Stopped receipt);
    Stop stop(Identity identity);
    /** Only after the application has durably stored this exact stop receipt. */
    void removeStopped(Identity identity, Stop receipt);
    /** V3 only: caller must first durably commit this exact stop receipt.
     * A missing container without that receipt is never termination evidence.
     * Legacy probe adapters must opt in; they cannot synthesize cleanup success. */
    default Removal confirmRemoval(Identity identity, Stop committedStop) {
        throw new UnsupportedOperationException("Exact removal confirmation is not supported by this adapter");
    }
    @Override void close() throws Exception;
}
