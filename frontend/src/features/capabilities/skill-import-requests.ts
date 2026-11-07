import type { PendingSkillImport } from './skill-import-coordinator';

const prefix = 'agentplatform:skill-import:';
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
export const skillImportScope = (user: string, space: string) => `${prefix}${user}:${space}`;

/** Stores only the identity and digest, never the archive bytes. */
export function saveSkillImport(scope: string, value?: PendingSkillImport): boolean {
  try {
    if (!scope.startsWith(prefix)) return false;
    if (value) sessionStorage.setItem(scope, JSON.stringify(value)); else sessionStorage.removeItem(scope);
    return true;
  } catch { return false; }
}

export function loadSkillImport(scope: string): PendingSkillImport | undefined {
  try {
    if (!scope.startsWith(prefix)) return;
    const raw = sessionStorage.getItem(scope); if (!raw) return;
    if (raw.length > 2048) throw new Error('invalid request');
    const value = JSON.parse(raw) as PendingSkillImport;
    const capability = value.body?.capabilityId;
    if (!uuid.test(value.requestId) || !uuid.test(value.completionId)
      || !Number.isSafeInteger(value.body?.sizeBytes) || value.body.sizeBytes < 0 || value.body.sizeBytes > 20971520
      || !/^[a-f0-9]{64}$/.test(value.body.sha256) || (capability != null && !uuid.test(capability))
      || (value.importId !== undefined && !uuid.test(value.importId))) throw new Error('invalid request');
    return { requestId: value.requestId, completionId: value.completionId,
      body: { sizeBytes: value.body.sizeBytes, sha256: value.body.sha256, ...(capability ? { capabilityId: capability } : {}) },
      ...(value.importId ? { importId: value.importId } : {}) };
  } catch { saveSkillImport(scope); return; }
}

export function clearSkillImports() {
  try { for (const key of Object.keys(sessionStorage)) if (key.startsWith(prefix)) sessionStorage.removeItem(key); }
  catch { /* Storage is unavailable; no additional write is permitted. */ }
}
