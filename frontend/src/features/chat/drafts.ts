import { defineStore } from 'pinia';
import { reactive } from 'vue';

export interface PendingSubmission { key: string; text: string; model: string; source: 'PLATFORM' | 'USER'; conversation: string; workspaceId?: string | null; permissionMode?: 'RISK_BASED' | 'FULL'; planMode?: boolean }
export interface ChatDraft { text: string; model: string; source: 'PLATFORM' | 'USER'; workspaceId?: string | null; permissionMode?: 'RISK_BASED' | 'FULL'; planMode?: boolean; pending?: PendingSubmission }
export interface PlanEditorDraft { title: string; markdown: string; baseRevision: number }
const prefix = 'agentplatform:chat-draft:';
export function clearChatDrafts() {
  for (const key of Object.keys(sessionStorage)) if (key.startsWith(prefix)) sessionStorage.removeItem(key);
}
export const useChatDrafts = defineStore('chat-drafts', () => {
  const drafts = reactive<Record<string, ChatDraft>>({});
  const planEditors=reactive<Record<string,PlanEditorDraft>>({});
  const planKey=(account:string,space:string,task:string)=>`${prefix}plan:${account}:${space}:${task}`;
  function loadPlan(account:string,space:string,task:string):PlanEditorDraft|undefined {
    const key=planKey(account,space,task);
    if(!planEditors[key])try {
      const value=JSON.parse(sessionStorage.getItem(key)??'null');
      if(value&&typeof value.title==='string'&&typeof value.markdown==='string'&&Number.isSafeInteger(value.baseRevision))planEditors[key]=value;
    }catch{/* A corrupt editor draft is never treated as server state. */}
    return planEditors[key];
  }
  function savePlan(account:string,space:string,task:string,value:PlanEditorDraft){
    if(!account||!space||!task)return;
    const key=planKey(account,space,task);planEditors[key]={...value};
    try{sessionStorage.setItem(key,JSON.stringify(value));}catch{/* Keep the live draft if browser storage is full. */}
  }
  function clearPlan(account:string,space:string,task:string){const key=planKey(account,space,task);delete planEditors[key];sessionStorage.removeItem(key);}
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
  function clear() { for (const key of Object.keys(drafts)) delete drafts[key];for(const key of Object.keys(planEditors))delete planEditors[key];clearChatDrafts(); }
  return { drafts, load, save, clear,loadPlan,savePlan,clearPlan };
});
