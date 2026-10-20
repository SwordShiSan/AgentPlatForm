import { describe, expect, it } from 'vitest';
import { applyChatEvent, SnapshotRequired, type ChatSnapshot } from './stream-state';
import type { PlatformEvent } from '../../shared/api/generated/events';

const id = '0199e000-0000-7000-8000-000000000002';
function snapshot(): ChatSnapshot {
  return { schemaVersion: 1, cursor: 3, messages: [{ id, taskId: id, role: 'ASSISTANT', blocks: [{ type: 'TEXT', blockId: id, text: 'a😀' }], status: 'STREAMING', version: 2 }], tasks: [], pending: [], artifacts: [] };
}
function event(seq = 4, offset = 2): PlatformEvent {
  return { schemaVersion: 1, eventId: id, spaceId: id, conversationId: id, seq, type: 'message.delta', occurredAt: '2026-10-16T00:00:00Z', entityVersion: 3, payload: { messageId: id, blockId: id, offset, text: '好' } } as PlatformEvent;
}
describe('committed chat cursor reducer', () => {
  it('applies codepoint offset immutably and deduplicates replay', () => {
    const before = snapshot(); const next = applyChatEvent(before, event());
    expect(next.messages[0]?.blocks[0]?.text).toBe('a😀好');
    expect(before.messages[0]?.blocks[0]?.text).toBe('a😀');
    expect(next.tasks).toBe(before.tasks);
    expect(next.pending).toBe(before.pending);
    expect(next.messages[0]).not.toBe(before.messages[0]);
    expect(applyChatEvent(next, event())).toBe(next);
  });
  it('requires canonical snapshot on missing sequence or UTF16 offset', () => {
    expect(() => applyChatEvent(snapshot(), event(5))).toThrow(SnapshotRequired);
    expect(() => applyChatEvent(snapshot(), event(4, 3))).toThrow(SnapshotRequired);
  });
  it('does not accept text for an unknown message', () => {
    const before = snapshot(); before.messages = [];
    expect(() => applyChatEvent(before, event())).toThrow(SnapshotRequired);
  });
});
