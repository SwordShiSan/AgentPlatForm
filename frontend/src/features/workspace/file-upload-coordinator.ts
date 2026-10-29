import { api } from '../../shared/api/client';
import { setCsrf } from '../../shared/api/session';
import { fileHash, transferFile } from './file-transfers';
import type { PendingFileRequest } from './file-requests';
/** Caller persists identity before any network write. No automatic unknown retransmission. */
export async function continueFileUpload(pending: PendingFileRequest, content: File | undefined, space: string,
  signal: AbortSignal, persist: () => void, progress: (value: number) => void = () => {}, queryOnly = false) {
  if (queryOnly) {
    if (!pending.uploadId) return undefined;
    const state = (await api.GET('/api/v1/uploads/{id}', { signal, params: { path: { id: pending.uploadId }, header: { 'X-Space-Id': space } } })).data;
    return state?.operationId ? (await api.GET('/api/v1/file-operations/{id}', { signal, params: { path: { id: state.operationId }, header: { 'X-Space-Id': space } } })).data : undefined;
  }
  const csrf = (await api.GET('/api/v1/auth/csrf', { signal })).data?.csrfToken;
  if (!csrf) throw new Error('安全验证暂不可用'); setCsrf(csrf);
  const header = { 'X-Space-Id': space, 'X-CSRF-Token': csrf };
  if (!pending.uploadId) {
    const result = await api.POST('/api/v1/workspaces/{id}/uploads', { signal, body: pending.body,
      params: { path: { id: pendingWorkspace(pending) }, header: { ...header, 'Idempotency-Key': pending.key, ...(pending.ifMatch ? { 'If-Match': pending.ifMatch } : {}) } } });
    if (!result.data) throw new Error('上传身份未确认'); pending.uploadId = result.data.id; persist();
  }
  let state = (await api.GET('/api/v1/uploads/{id}', { signal, params: { path: { id: pending.uploadId }, header } })).data;
  if (state?.state === 'CREATED' && content) {
    if (content.size !== pending.body.bytes || await fileHash(content, signal) !== pending.body.sha256) throw new Error('内容与原请求不一致');
    state = await transferFile(state.id, content, space, csrf, signal, progress);
  }
  if (state?.state === 'VERIFIED') {
    const result = await api.POST('/api/v1/uploads/{id}/complete', { signal, params: { path: { id: state.id }, header } });
    return result.data;
  }
  if (state?.operationId) return (await api.GET('/api/v1/file-operations/{id}', { signal, params: { path: { id: state.operationId }, header } })).data;
  return undefined;
}
// Workspace is transport context, deliberately not part of the public upload body.
const workspaces = new WeakMap<PendingFileRequest, string>();
export function bindUploadWorkspace(pending: PendingFileRequest, workspace: string) { workspaces.set(pending, workspace); return pending; }
function pendingWorkspace(pending: PendingFileRequest) { const id = workspaces.get(pending); if (!id) throw new Error('缺少工作区身份'); return id; }
