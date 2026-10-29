import { textPreview } from './file-transfers';
import type { PendingFileRequest } from './file-requests';
export const DRAFT_PREFIX = 'agentplatform:editor:';
export interface TextFormat { bom: boolean; eol: '\n' | '\r\n' }
export interface EditorDraft {
  fileId: string; versionId: string; revision: number; path: string;
  base: string; text: string; format: TextFormat;
  pending?: PendingFileRequest; submitted?: string;
}
export function decodeText(raw: ArrayBuffer) {
  const bytes = new Uint8Array(raw);
  const bom = bytes[0] === 239 && bytes[1] === 187 && bytes[2] === 191;
  const text = textPreview(raw);
  return { text: text.replace(/\r\n/g, '\n'), format: { bom, eol: text.includes('\r\n') ? '\r\n' : '\n' } as TextFormat };
}
export function encodeText(text: string, format: TextFormat): Uint8Array<ArrayBuffer> {
  const raw = new TextEncoder().encode((format.bom ? '\ufeff' : '') + text.replace(/\r\n/g, '\n').replace(/\n/g, format.eol));
  if (raw.length > 2 * 1024 * 1024) throw new Error('文本超过 2 MiB，未提交');
  return raw;
}
export function draftKey(user: string, space: string, workspace: string, file: string) {
  return `${DRAFT_PREFIX}${user}:${space}:${workspace}:${file}`;
}
export function persistDraft(key: string, draft: EditorDraft): boolean {
  try {
    const raw = JSON.stringify(draft); let total = new TextEncoder().encode(raw).length;
    for (const k of Object.keys(sessionStorage)) if (k.startsWith(DRAFT_PREFIX) && k !== key) total += new TextEncoder().encode(sessionStorage.getItem(k) ?? '').length;
    if (total > 4 * 1024 * 1024) return false;
    sessionStorage.setItem(key, raw); return true;
  } catch { return false; }
}
export function restoreDraft(key: string): EditorDraft | undefined {
  try {
    const raw = sessionStorage.getItem(key); if (!raw || raw.length > 8 * 1024 * 1024) return;
    const d = JSON.parse(raw) as EditorDraft;
    if (typeof d.text !== 'string' || typeof d.base !== 'string' || !Number.isSafeInteger(d.revision) || !d.fileId || !d.versionId
      || !d.format || typeof d.format.bom !== 'boolean' || !['\n', '\r\n'].includes(d.format.eol)) return;
    return d;
  } catch { return; }
}
export function clearEditorDrafts() {
  try { for (const key of Object.keys(sessionStorage)) if (key.startsWith(DRAFT_PREFIX)) sessionStorage.removeItem(key); } catch { /* No storage. */ }
}
