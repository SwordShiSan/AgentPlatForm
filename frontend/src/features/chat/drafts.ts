import { defineStore } from 'pinia';
import { reactive } from 'vue';

export interface PendingSubmission { key: string; text: string; model: string; source: 'PLATFORM' | 'USER'; conversation: string }
export interface ChatDraft { text: string; model: string; source: 'PLATFORM' | 'USER'; pending?: PendingSubmission }
const prefix = 'agentplatform:chat-draft:';
export function clearChatDrafts() {
  for (const key of Object.keys(sessionStorage)) if (key.startsWith(prefix)) sessionStorage.removeItem(key);
}
export const useChatDrafts = defineStore('chat-drafts', () => {
  const drafts = reactive<Record<string, ChatDraft>>({});
  function load(account: string, space: string, conversation: string): ChatDraft {
    const key = `${prefix}${account}:${space}:${conversation || 'new'}`;
    if (!drafts[key]) {
      let saved: ChatDraft | undefined;
      try { const value = JSON.parse(sessionStorage.getItem(key) ?? 'null'); if (value && typeof value.text === 'string' && typeof value.model === 'string' && ['PLATFORM', 'USER'].includes(value.source)) saved = value; } catch { /* Ignore corrupt local data. */ }
      drafts[key] = saved ?? { text: '', model: '', source: 'PLATFORM' };
    }
    return drafts[key]!;
  }
  function save(account: string, space: string, conversation: string, value: ChatDraft) {
    if (account && space) {
      const key = `${prefix}${account}:${space}:${conversation || 'new'}`;
      drafts[key] = { ...value };
      sessionStorage.setItem(key, JSON.stringify(value));
    }
  }
  function clear() { for (const key of Object.keys(drafts)) delete drafts[key]; clearChatDrafts(); }
  return { drafts, load, save, clear };
});
