import type { components } from '../../shared/api/generated/http';
import type { PlatformEvent } from '../../shared/api/generated/events';

export type ChatSnapshot = components['schemas']['ChatSnapshot'];
export class SnapshotRequired extends Error {}

/** Only committed envelope events enter the cache. Offsets count codepoints, not UTF16 units. */
export function applyChatEvent(previous: ChatSnapshot, event: PlatformEvent): ChatSnapshot {
  if (event.seq <= previous.cursor) return previous;
  if (event.seq !== previous.cursor + 1) throw new SnapshotRequired('Event cursor gap');
  const next = structuredClone(previous);
  const payload = event.payload as Record<string, unknown>;
  if (event.type === 'task.accepted') throw new SnapshotRequired('New task needs canonical message snapshot');
  if (event.type === 'task.state_changed') {
    const task = next.tasks.find(item => item.taskId === payload.taskId);
    if (!task) throw new SnapshotRequired('Unknown task');
    task.status = payload.status as typeof task.status;
    task.reasonCode = typeof payload.reasonCode === 'string' ? payload.reasonCode : null;
    if (typeof payload.cancelRequested === 'boolean') task.cancelRequested = payload.cancelRequested;
    task.version = event.entityVersion;
  }
  if (event.type.startsWith('message.')) {
    const message = next.messages.find(item => item.id === payload.messageId);
    if (!message) throw new SnapshotRequired('Unknown message');
    if (event.type === 'message.delta') {
      const block = message.blocks.find(item => item.blockId === payload.blockId);
      if (!block || typeof payload.text !== 'string' || Array.from(block.text).length !== payload.offset) {
        throw new SnapshotRequired('Text offset differs');
      }
      block.text += payload.text;
    } else if (event.type === 'message.completed') {
      message.status = payload.status as typeof message.status;
    } else if (event.type !== 'message.started') {
      throw new SnapshotRequired('Unsupported message event');
    }
    message.version = event.entityVersion;
  }
  next.cursor = event.seq;
  return next;
}
