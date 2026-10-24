import type { components } from '../../shared/api/generated/http';
export interface PendingFileRequest {
  key: string;
  body: components['schemas']['CreateFileUpload'];
  ifMatch?: string;
  uploadId?: string;
}
const prefix = 'agentplatform:file-upload:';
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
export const fileRequestScope = (user: string, space: string, workspace: string) => `${prefix}${user}:${space}:${workspace}`;
export function saveFileRequest(scope: string, value?: PendingFileRequest) {
  try { if (value) sessionStorage.setItem(scope, JSON.stringify(value)); else sessionStorage.removeItem(scope); }
  catch { /* Preserve the in-memory request; a storage failure never authorizes replay. */ }
}
export function loadFileRequest(scope: string): PendingFileRequest | undefined {
  try {
    const raw = sessionStorage.getItem(scope); if (!raw) return;
    if (raw.length > 16384) throw new Error('oversized request identity');
    const value = JSON.parse(raw) as PendingFileRequest;
    if (!uuid.test(value.key) || typeof value.body?.path !== 'string' || value.body.path.length > 4096
      || !Number.isSafeInteger(value.body.bytes) || value.body.bytes < 0 || value.body.bytes > 104857600
      || !/^[a-f0-9]{64}$/.test(value.body.sha256) || (value.uploadId !== undefined && !uuid.test(value.uploadId))
      || (value.body.fileId !== undefined && !uuid.test(value.body.fileId))
      || (value.body.baseVersionId !== undefined && !uuid.test(value.body.baseVersionId))
      || (value.ifMatch !== undefined && !/^"[1-9][0-9]*"$/.test(value.ifMatch))) throw new Error('invalid request identity');
    return { key: value.key, body: { path: value.body.path, bytes: value.body.bytes, sha256: value.body.sha256,
      ...(value.body.fileId ? { fileId: value.body.fileId, baseVersionId: value.body.baseVersionId } : {}) },
      ...(value.uploadId ? { uploadId: value.uploadId } : {}), ...(value.ifMatch ? { ifMatch: value.ifMatch } : {}) };
  } catch { saveFileRequest(scope); return; }
}
export function clearFileRequests() {
  try { for (const key of Object.keys(sessionStorage)) if (key.startsWith(prefix)) sessionStorage.removeItem(key); }
  catch { /* Session storage is unavailable. */ }
}
