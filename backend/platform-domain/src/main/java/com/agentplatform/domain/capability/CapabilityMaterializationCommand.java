package com.agentplatform.domain.capability;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Signed, server-created request to verify one frozen capability set on the runner. */
public record CapabilityMaterializationCommand(UUID commandId, UUID operationId, UUID spaceId,
        UUID taskId, UUID attemptId, UUID actorId, UUID ownerId, UUID workspaceId,
        long generation, long fence, String manifestHash, List<CapabilityBindings.Selection> selections) {
    public CapabilityMaterializationCommand {
        Objects.requireNonNull(commandId); Objects.requireNonNull(operationId); Objects.requireNonNull(spaceId);
        Objects.requireNonNull(taskId); Objects.requireNonNull(attemptId); Objects.requireNonNull(actorId);
        Objects.requireNonNull(ownerId); Objects.requireNonNull(workspaceId); Objects.requireNonNull(selections);
        if (generation < 1 || fence < 1 || manifestHash == null || !manifestHash.matches("[a-f0-9]{64}")
                || selections.isEmpty() || selections.size() > CapabilityBindings.MAX_BINDINGS
                || selections.stream().anyMatch(Objects::isNull)
                || new HashSet<>(selections.stream().map(CapabilityBindings.Selection::capabilityId).toList()).size() != selections.size())
            throw new IllegalArgumentException("INVALID_CAPABILITY_MATERIALIZATION_COMMAND");
        selections = List.copyOf(selections);
    }
}
