import { beforeEach, expect, it, vi } from 'vitest';
import { storeSessionDraft } from './draft-storage';

beforeEach(() => {
  const data: Record<string, string> = {};
  vi.stubGlobal('sessionStorage', new Proxy(data, { get(target, key) {
    if (key === 'getItem') return (name: string) => target[name] ?? null;
    if (key === 'setItem') return (name: string, value: string) => { target[name] = value; };
    return Reflect.get(target, key);
  } }));
});

it('shares the UTF8 session allowance across chat and editor without eviction', () => {
  expect(storeSessionDraft('agentplatform:editor:a', { text: '中'.repeat(700000) })).toBe(true);
  expect(storeSessionDraft('agentplatform:chat-draft:a', { text: 'a'.repeat(2100000) })).toBe(false);
  expect(sessionStorage.getItem('agentplatform:editor:a')).not.toBeNull();
  expect(storeSessionDraft('agentplatform:chat-draft:a', { text: 'small' })).toBe(true);
  expect(storeSessionDraft('other:a', { text: 'not a managed draft' })).toBe(false);
});

it('counts replacement once and reports storage failure without dropping existing content', () => {
  expect(storeSessionDraft('agentplatform:chat-draft:a', { text: 'x'.repeat(3000000) })).toBe(true);
  expect(storeSessionDraft('agentplatform:chat-draft:a', { text: 'y'.repeat(3000000) })).toBe(true);
  vi.stubGlobal('sessionStorage', { getItem: () => null, setItem: () => { throw new Error('quota'); } });
  expect(storeSessionDraft('agentplatform:chat-draft:b', { text: 'retained in caller' })).toBe(false);
});
