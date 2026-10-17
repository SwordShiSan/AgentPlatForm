package com.agentplatform.types;

import java.util.UUID;

/** Server authority; caller-supplied trace headers never establish execution identity. */
public final class RequestTrace {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    private RequestTrace() {}
    public static UUID current() { UUID id=CURRENT.get(); return id==null?UUID.randomUUID():id; }
    public static void enter(UUID id) { CURRENT.set(id); }
    public static void clear() { CURRENT.remove(); }
}
