import { api } from '../../shared/api/client';
import { setCsrf } from '../../shared/api/session';
import { fileHash, transferSkillArchive } from '../workspace/file-transfers';
import type { components } from '../../shared/api/generated/http';

/** Only request identities and hashes may be persisted; never persist the archive itself. */
export interface PendingSkillImport {
  requestId: string;
  completionId: string;
  body: components['schemas']['SkillImportCreate'];
  importId?: string;
}

export async function skillImportStatus(pending: PendingSkillImport, space: string, signal: AbortSignal,
  persist: () => void) {
  const header = { 'X-Space-Id': space };
  const result = pending.importId
    ? await api.GET('/api/v1/skills/imports/{id}', { signal, params: { path: { id: pending.importId }, header } })
    : await api.GET('/api/v1/skills/imports/requests/{requestId}', { signal, params: { path: { requestId: pending.requestId }, header } });
  if (!result.data) throw new Error('导入状态尚未确认');
  if (!pending.importId) { pending.importId = result.data.id; persist(); }
  return result.data;
}

/** Caller persists the frozen request before calling; polling never invokes a write. */
export async function continueSkillImport(pending: PendingSkillImport, file: File | undefined, space: string,
  signal: AbortSignal, persist: () => void, progress: (value: number) => void = () => {}) {
  const csrf = (await api.GET('/api/v1/auth/csrf', { signal })).data?.csrfToken;
  if (!csrf) throw new Error('安全验证暂不可用');
  setCsrf(csrf);
  const header = { 'X-Space-Id': space, 'X-CSRF-Token': csrf };
  if (!pending.importId) {
    const result = await api.POST('/api/v1/skills/imports', { signal, body: pending.body,
      params: { header: { ...header, 'Idempotency-Key': pending.requestId } } });
    if (!result.data) throw new Error('导入身份尚未确认');
    pending.importId = result.data.id; persist();
  }
  let state = await skillImportStatus(pending, space, signal, persist);
  if (state.state === 'CREATED' && file) {
    if (file.size > 20 * 1024 * 1024 || file.size !== pending.body.sizeBytes || await fileHash(file, signal) !== pending.body.sha256)
      throw new Error('文件与原导入请求不一致');
    state = await transferSkillArchive(state.id, file, space, csrf, signal, progress);
  }
  if (state.state === 'VERIFIED') {
    const result = await api.POST('/api/v1/skills/imports/{id}/complete', { signal,
      params: { path: { id: state.id }, header: { ...header, 'Idempotency-Key': pending.completionId } } });
    if (!result.data) throw new Error('导入作业尚未确认');
    return result.data;
  }
  return state;
}
