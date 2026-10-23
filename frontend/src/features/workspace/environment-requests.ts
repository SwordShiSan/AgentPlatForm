// UI request identity only. Environment state remains authoritative in Query/server storage.
export interface PendingEnvironmentRequest { key: string; version: number; action: 'PREPARE' | 'RETRY' }
const prefix = 'agentplatform:environment-request:';
export function environmentRequestScope(account: string, space: string, workspace: string) {
  return `${prefix}${account}:${space}:${workspace}`;
}
export function loadEnvironmentRequest(scope: string): PendingEnvironmentRequest | undefined {
  try {
    const raw = sessionStorage.getItem(scope) ?? 'null';
    if (raw.length > 512) { sessionStorage.removeItem(scope); return undefined; }
    const value = JSON.parse(raw);
    if (value && typeof value.key === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.key)
      && Number.isSafeInteger(value.version) && value.version >= 0 && ['PREPARE', 'RETRY'].includes(value.action)) return { key: value.key, version: value.version, action: value.action };
    sessionStorage.removeItem(scope);
  } catch { /* Corrupt/unavailable browser storage is not a server fact. */ }
  return undefined;
}
export function saveEnvironmentRequest(scope: string, value?: PendingEnvironmentRequest) {
  try { if (value) sessionStorage.setItem(scope, JSON.stringify({ key: value.key, version: value.version, action: value.action })); else sessionStorage.removeItem(scope); }
  catch { /* Preserve the in-memory request when browser storage is unavailable. */ }
}
export function clearEnvironmentRequests() {
  try { for (const key of Object.keys(sessionStorage)) if (key.startsWith(prefix)) sessionStorage.removeItem(key); }
  catch { /* No persistent storage to clear. */ }
}
