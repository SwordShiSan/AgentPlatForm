import { api } from '../../shared/api/client';
import { setCsrf } from '../../shared/api/session';
import { newRequestId } from '../../shared/api/request-id';
import type { components } from '../../shared/api/generated/http';

export type PreviewRequest = components['schemas']['PreviewRequest'];
export interface PendingPreview { key: string; body: PreviewRequest; previewId?: string }
const prefix = 'agentplatform:preview-request:';
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function previewScope(user: string, space: string, file: string, version: string, format: PreviewRequest['format']) {
  return `${prefix}${user}:${space}:${file}:${version}:${format}`;
}
export function pendingPreview(scope: string, body: PreviewRequest): PendingPreview {
  try {
    const raw = sessionStorage.getItem(scope);
    if (raw && raw.length <= 1024) {
      const value = JSON.parse(raw);
      if (uuid.test(value.key) && value.body?.versionId === body.versionId && value.body?.format === body.format
        && (value.previewId === undefined || uuid.test(value.previewId))) return { key: value.key, body: { ...body }, ...(value.previewId ? { previewId: value.previewId } : {}) };
    }
  } catch { /* Keep the request in this page when session storage is unavailable. */ }
  const value = { key: newRequestId(), body: { ...body } }; savePreview(scope, value); return value;
}
export function savePreview(scope: string, value: PendingPreview) {
  try { sessionStorage.setItem(scope, JSON.stringify(value)); } catch { /* No content is stored; an unavailable store only limits refresh recovery. */ }
}
export function clearPreviewRequests() {
  try { for (const key of Object.keys(sessionStorage)) if (key.startsWith(prefix)) sessionStorage.removeItem(key); }
  catch { /* Session storage unavailable. */ }
}
export async function previewHeaders(space: string, signal: AbortSignal) {
  const csrf = (await api.GET('/api/v1/auth/csrf', { signal })).data?.csrfToken;
  if (!csrf) throw new Error('安全验证暂不可用'); setCsrf(csrf);
  return { 'X-Space-Id': space, 'X-CSRF-Token': csrf };
}
export async function submitPreview(file: string, pending: PendingPreview, space: string, signal: AbortSignal) {
  const headers = await previewHeaders(space, signal);
  const result = await api.POST('/api/v1/files/{id}/previews', { signal, body: pending.body,
    params: { path: { id: file }, header: { ...headers, 'Idempotency-Key': pending.key } } });
  if (!result.data) throw new Error('预览受理结果未确认'); return result.data;
}
