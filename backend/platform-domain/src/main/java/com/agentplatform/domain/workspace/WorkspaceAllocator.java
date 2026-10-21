package com.agentplatform.domain.workspace;

import com.agentplatform.domain.workspace.WorkspaceData.Command;

public interface WorkspaceAllocator {
    /** May only be raised when the adapter proves no allocation effect was started. */
    final class RejectedBeforeEffect extends RuntimeException {
        public RejectedBeforeEffect(String code) { super(code); }
    }
    String provision(Command command);
    /** Read an already accepted physical operation; must never allocate or restart anything. */
    default Result inspect(Command command) { return null; }
    /** Caller must first durably commit a definite receipt; never removes workspace data. */
    default void releaseCompletedHelper(Command command) {}
    record Result(String state, String effectState, String allocationHash, String errorCode) {}
}
