import type { paths } from '../../shared/api/generated/http';
import { api } from '../../shared/api/client';
import { setCsrf } from '../../shared/api/session';

export type MoveBody = paths['/api/v1/files/{id}/move']['post']['requestBody']['content']['application/json'];
export type RestoreBody = paths['/api/v1/files/{id}/restore']['post']['requestBody']['content']['application/json'];
export interface PendingMutation {
  kind: 'MOVE' | 'DELETE' | 'RESTORE';
  key: string;
  fileId: string;
  ifMatch: string;
  body?: MoveBody | RestoreBody;
  operationId?: string;
}
const prefix = 'agentplatform:file-mutation:';
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
export const mutationScope = (user: string, space: string, workspace: string, file: string) => `${prefix}${user}:${space}:${workspace}:${file}`;
export function persistMutation(scope: string, value?: PendingMutation) {
  try { if (value) sessionStorage.setItem(scope, JSON.stringify(value)); else sessionStorage.removeItem(scope); return true; }
  catch { return false; }
}
export function loadMutation(scope: string): PendingMutation | undefined {
  try {
    const raw = sessionStorage.getItem(scope); if (!raw) return;
    if (raw.length > 32768) return;
    const value = JSON.parse(raw) as PendingMutation;
    if (!uuid.test(value.key) || !uuid.test(value.fileId) || !scope.endsWith(`:${value.fileId}`)
      || !/^"[1-9][0-9]*"$/.test(value.ifMatch) || !['MOVE', 'DELETE', 'RESTORE'].includes(value.kind)
      || value.operationId !== undefined && !uuid.test(value.operationId)) return;
    if (value.kind !== 'DELETE' && (!value.body || typeof value.body !== 'object' || Array.isArray(value.body))) return;
    return value;
  } catch { return; }
}
export function clearMutationRequests() {
  try { for (const key of Object.keys(sessionStorage)) if (key.startsWith(prefix)) sessionStorage.removeItem(key); }
  catch { /* Browser session storage is unavailable. */ }
}
export async function readMutation(value: PendingMutation, space: string, signal: AbortSignal) {
  return (await api.GET('/api/v1/files/{id}/mutation-requests/{requestId}', {
    signal, params: { path: { id: value.fileId, requestId: value.key }, header: { 'X-Space-Id': space } },
  })).data!;
}
export async function submitMutation(value: PendingMutation, space: string, signal: AbortSignal) {
  const token = (await api.GET('/api/v1/auth/csrf', { signal })).data?.csrfToken;
  if (!token) throw new Error('安全验证暂不可用'); setCsrf(token);
  const params = { path: { id: value.fileId }, header: {
    'X-Space-Id': space, 'X-CSRF-Token': token, 'Idempotency-Key': value.key, 'If-Match': value.ifMatch,
  } };
  if (value.kind === 'DELETE') return (await api.DELETE('/api/v1/files/{id}', { signal, params })).data!;
  if (value.kind === 'MOVE') return (await api.POST('/api/v1/files/{id}/move', { signal, params, body: value.body as MoveBody })).data!;
  const restore = value.body as RestoreBody;
  const target = { targetParentId: restore.targetParentId, name: restore.name };
  const body = restore.sourceVersionId ? { ...target, sourceVersionId: restore.sourceVersionId }
    : { ...target, deletionOperationId: restore.deletionOperationId! };
  return (await api.POST('/api/v1/files/{id}/restore', { signal, params, body })).data!;
}

/** Resolves only real directory identities using per-directory pages, never a whole-tree request. */
export async function resolveDirectory(workspace: string, space: string, path: string, signal: AbortSignal) {
  const normalized = path.normalize('NFC').replace(/^\/+|\/+$/g, '');
  if (!normalized) return null;
  const parts = normalized.split('/'); if (parts.some(p => !p || p === '.' || p === '..')) throw new Error('目标目录无效');
  let parent = '', id: string | undefined;
  for (const name of parts) {
    let cursor: string | undefined; id = undefined;
    do {
      const data = (await api.GET('/api/v1/workspaces/{id}/files', { signal,
        params: { path: { id: workspace }, query: { parent, cursor, limit: 100 }, header: { 'X-Space-Id': space } } })).data!;
      const found = data.items.find(item => item.name === name && item.kind === 'DIRECTORY' && item.collectionState === 'CONFIRMED');
      if (found) { id = found.id; parent = found.path; break; }
      cursor = data.nextCursor ?? undefined;
    } while (cursor);
    if (!id) throw new Error('目标目录不存在或尚未确认');
  }
  return id!;
}
