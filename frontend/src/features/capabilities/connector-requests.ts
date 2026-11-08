/** Session-only recovery identities. Never put a connector secret in this record. */
export interface ConnectorRequests {
  credentialRequest?: string;
  configurationRequest?: string;
  probeRequest?: string;
  activation?: { requestId: string; connectorId: string; versionId?: string; revision: number };
}
const prefix = 'agentplatform:connector-requests:';
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
export const connectorScope = (user: string, space: string) => `${prefix}${user}:${space}`;
export function saveConnectorRequests(scope: string, value?: ConnectorRequests): boolean {
  try {
    if (!scope.startsWith(prefix)) return false;
    if (value) {
      const activation = value.activation;
      if (activation && (!uuid.test(activation.requestId) || !uuid.test(activation.connectorId)
        || (activation.versionId !== undefined && !uuid.test(activation.versionId))
        || !Number.isSafeInteger(activation.revision) || activation.revision < 0)) return false;
      const safe = { ...(value.credentialRequest ? { credentialRequest: value.credentialRequest } : {}),
        ...(value.configurationRequest ? { configurationRequest: value.configurationRequest } : {}),
        ...(value.probeRequest ? { probeRequest: value.probeRequest } : {}),
        ...(activation ? { activation: { requestId: activation.requestId, connectorId: activation.connectorId,
          ...(activation.versionId ? { versionId: activation.versionId } : {}), revision: activation.revision } } : {}) };
      if ([safe.credentialRequest, safe.configurationRequest, safe.probeRequest].some(id => id !== undefined && !uuid.test(id))) return false;
      sessionStorage.setItem(scope, JSON.stringify(safe));
    } else sessionStorage.removeItem(scope);
    return true;
  } catch { return false; }
}
export function loadConnectorRequests(scope: string): ConnectorRequests {
  try {
    if (!scope.startsWith(prefix)) return {};
    const raw = sessionStorage.getItem(scope); if (!raw) return {};
    if (raw.length > 512) throw new Error('invalid request identity');
    const data = JSON.parse(raw) as ConnectorRequests;
    const activation = data.activation;
    if (activation && (!uuid.test(activation.requestId) || !uuid.test(activation.connectorId)
      || (activation.versionId !== undefined && !uuid.test(activation.versionId))
      || !Number.isSafeInteger(activation.revision) || activation.revision < 0)) throw new Error('invalid request identity');
    const result = { ...(data.credentialRequest ? { credentialRequest: data.credentialRequest } : {}),
      ...(data.configurationRequest ? { configurationRequest: data.configurationRequest } : {}),
      ...(data.probeRequest ? { probeRequest: data.probeRequest } : {}),
      ...(activation ? { activation: { requestId: activation.requestId, connectorId: activation.connectorId,
        ...(activation.versionId ? { versionId: activation.versionId } : {}), revision: activation.revision } } : {}) };
    if ([result.credentialRequest, result.configurationRequest, result.probeRequest].some(id => id !== undefined && (typeof id !== 'string' || !uuid.test(id)))) throw new Error('invalid request identity');
    return result;
  } catch { saveConnectorRequests(scope); return {}; }
}
export function clearConnectorRequests() {
  try { for (const key of Object.keys(sessionStorage)) if (key.startsWith(prefix)) sessionStorage.removeItem(key); }
  catch { /* Unavailable storage is not proof of a completed request. */ }
}
