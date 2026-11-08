package com.agentplatform.domain.capability;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Resolves stored selections; authorization and availability are checked separately. */
public final class CapabilityBindings {
    public static final int MAX_BINDINGS = 100;

    public enum Source { PERSONAL, WORKSPACE }

    public record Selection(UUID capabilityId, UUID versionId) {
        public Selection {
            Objects.requireNonNull(capabilityId, "capabilityId");
            Objects.requireNonNull(versionId, "versionId");
        }
    }

    public record Resolved(Source source, List<Selection> selections) {
        public Resolved {
            Objects.requireNonNull(source, "source");
            selections = validate(selections);
        }
    }

    private CapabilityBindings() {}

    /** A null workspace selection inherits; an empty list explicitly clears. */
    public static Resolved resolve(List<Selection> personal, List<Selection> workspace) {
        List<Selection> checkedPersonal = validate(personal);
        return workspace == null
                ? new Resolved(Source.PERSONAL, checkedPersonal)
                : new Resolved(Source.WORKSPACE, workspace);
    }

    private static List<Selection> validate(List<Selection> selections) {
        Objects.requireNonNull(selections, "selections");
        if (selections.size() > MAX_BINDINGS) throw new IllegalArgumentException("CAPABILITY_BINDING_LIMIT");
        var ids = new HashSet<UUID>();
        for (Selection selection : selections) {
            Objects.requireNonNull(selection, "selection");
            if (!ids.add(selection.capabilityId())) throw new IllegalArgumentException("DUPLICATE_CAPABILITY_BINDING");
        }
        return List.copyOf(selections);
    }
}
