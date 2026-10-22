/* Generated from contracts. Do not edit manually. */

/**
 * Version1 envelope; R1.2 freezes task/text/usage payloads below. Other enumerated capabilities remain unimplemented.
 */
export type PlatformEvent = {
  [k: string]: unknown;
} & {
  schemaVersion: 1;
  eventId: string;
  spaceId: string;
  conversationId: string | null;
  taskId?: string | null;
  attemptId?: string | null;
  seq: number;
  type:
    | "task.accepted"
    | "task.state_changed"
    | "message.started"
    | "message.delta"
    | "message.completed"
    | "message.replaced"
    | "tool.prepared"
    | "tool.started"
    | "tool.completed"
    | "interaction.opened"
    | "interaction.resolved"
    | "plan.revised"
    | "artifact.created"
    | "file.changed"
    | "usage.updated"
    | "workspace.state_changed"
    | "workspace.environment_changed"
    | "preview.state_changed"
    | "capability.binding_changed"
    | "credential.revoked"
    | "membership.changed"
    | "workflow.node_changed"
    | "automation.occurrence_changed"
    | "task.children_changed"
    | "context.updated"
    | "verification.updated";
  occurredAt: string;
  entityVersion: number;
  payload: {
    [k: string]: unknown;
  };
};
