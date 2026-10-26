import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useChatDrafts } from './drafts';
describe('isolated chat drafts', () => {
  beforeEach(() => { vi.stubGlobal('sessionStorage', { getItem(key: string) { return (this as Record<string, unknown>)[key] ?? null; }, setItem(key: string, value: string) { (this as Record<string, unknown>)[key] = value; }, removeItem(key: string) { delete (this as Record<string, unknown>)[key]; } }); setActivePinia(createPinia()); });
  it('restores exact uncertain request after reload without sharing across accounts or conversations', () => {
    const store = useChatDrafts(); const pending = { key: 'one-key', text: 'hello 🧪', model: 'flash', source: 'USER' as const, conversation: '', planMode: true };
    store.save('alice', 'personal-a', '', { text: pending.text, model: 'flash', source: 'USER', planMode: true, pending });
    expect(store.load('alice', 'personal-a', '').pending).toEqual(pending);
    expect(store.load('alice', 'personal-a', '').planMode).toBe(true);
    expect(store.load('bob', 'personal-b', '').text).toBe('');
    expect(store.load('alice', 'personal-a', 'other').text).toBe('');
    setActivePinia(createPinia()); expect(useChatDrafts().load('alice', 'personal-a', '').pending).toEqual(pending);
  });
  it('logout clears stored drafts and in-memory copies while retaining unrelated UI storage', () => {
    const store = useChatDrafts(); sessionStorage.setItem('theme', 'dark');
    store.save('alice', 'a', '', { text: 'private', model: 'flash', source: 'PLATFORM' }); store.clear();
    expect(store.load('alice', 'a', '').text).toBe(''); expect(sessionStorage.getItem('theme')).toBe('dark');
    store.savePlan('alice','a','task',{title:'Plan draft',markdown:'private edit',baseRevision:1});
    expect(store.loadPlan('bob','b','task')).toBeUndefined();
    store.clear();expect(store.loadPlan('alice','a','task')).toBeUndefined();
  });
});
