import type { components } from '../../shared/api/generated/http';
export interface PendingBinding {
  requestId: string; workspaceId?: string; revision: number;
  body: components['schemas']['CapabilityBindingUpdate'];
}
const prefix = 'agentplatform:capability-binding:';
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
export const bindingScope = (user: string, space: string) => `${prefix}${user}:${space}`;
export function saveBindingRequest(scope: string, value?: PendingBinding): boolean {
  try { if (!scope.startsWith(prefix)) return false;
    if (value) sessionStorage.setItem(scope, JSON.stringify({ requestId: value.requestId, revision: value.revision,
      ...(value.workspaceId ? { workspaceId: value.workspaceId } : {}), body: { inheritPersonal: value.body.inheritPersonal,
        selections: value.body.selections.map(item => ({ capabilityId: item.capabilityId, versionId: item.versionId })) } }));
    else sessionStorage.removeItem(scope); return true;
  } catch { return false; }
}
export function loadBindingRequest(scope: string): PendingBinding | undefined {
  try {
    if (!scope.startsWith(prefix)) return;
    const raw = sessionStorage.getItem(scope); if (!raw) return;
    if (raw.length > 16000) throw new Error('invalid request');
    const value = JSON.parse(raw) as PendingBinding;
    if (!uuid.test(value.requestId) || (value.workspaceId !== undefined && !uuid.test(value.workspaceId))
      || !Number.isSafeInteger(value.revision) || value.revision < 0 || typeof value.body?.inheritPersonal !== 'boolean'
      || !Array.isArray(value.body.selections) || value.body.selections.length > 100
      || value.body.selections.some(item => !uuid.test(item.capabilityId) || !uuid.test(item.versionId))) throw new Error('invalid request');
    return { requestId: value.requestId, revision: value.revision, ...(value.workspaceId ? { workspaceId: value.workspaceId } : {}),
      body: { inheritPersonal: value.body.inheritPersonal, selections: value.body.selections.map(item => ({ capabilityId: item.capabilityId, versionId: item.versionId })) } };
  } catch { saveBindingRequest(scope); return; }
}
export function clearBindingRequests() {
  try { for (const key of Object.keys(sessionStorage)) if (key.startsWith(prefix)) sessionStorage.removeItem(key); } catch { /* unavailable */ }
}
