import type { components } from '../../shared/api/generated/http';
import type { PlatformEvent } from '../../shared/api/generated/events';

export type ChatSnapshot = components['schemas']['ChatSnapshot'];
export class SnapshotRequired extends Error {}

/** Only committed envelope events enter the cache. Offsets count codepoints, not UTF16 units. */
export function applyChatEvent(previous: ChatSnapshot, event: PlatformEvent): ChatSnapshot {
  if (event.seq <= previous.cursor) return previous;
  if (event.seq !== previous.cursor + 1) throw new SnapshotRequired('Event cursor gap');
  if (!['task.accepted', 'task.state_changed', 'message.started', 'message.delta', 'message.completed', 'usage.updated'].includes(event.type)) {
    throw new SnapshotRequired('Unsupported projection event');
  }
  const next = { ...previous };
  const payload = event.payload as Record<string, unknown>;
  if (event.type === 'task.accepted') throw new SnapshotRequired('New task needs canonical message snapshot');
  if (event.type === 'task.state_changed') {
    const index = next.tasks.findIndex(item => item.taskId === payload.taskId);
    const original = next.tasks[index];
    const task = original ? { ...original } : undefined;
    if (!task) throw new SnapshotRequired('Unknown task');
    next.tasks = [...next.tasks]; next.tasks[index] = task;
    task.status = payload.status as typeof task.status;
    task.reasonCode = typeof payload.reasonCode === 'string' ? payload.reasonCode : null;
    if (typeof payload.cancelRequested === 'boolean') task.cancelRequested = payload.cancelRequested;
    task.version = event.entityVersion;
  }
  if (event.type.startsWith('message.')) {
    const index = next.messages.findIndex(item => item.id === payload.messageId);
    const original = next.messages[index];
    const message = original ? { ...original } : undefined;
    if (!message) throw new SnapshotRequired('Unknown message');
    next.messages = [...next.messages]; next.messages[index] = message;
    if (event.type === 'message.delta') {
      const blockIndex = message.blocks.findIndex(item => item.blockId === payload.blockId);
      const originalBlock = message.blocks[blockIndex];
      const block = originalBlock ? { ...originalBlock } : undefined;
      if (!block || typeof payload.text !== 'string' || Array.from(block.text).length !== payload.offset) {
        throw new SnapshotRequired('Text offset differs');
      }
      block.text += payload.text;
      message.blocks = [...message.blocks]; message.blocks[blockIndex] = block;
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
