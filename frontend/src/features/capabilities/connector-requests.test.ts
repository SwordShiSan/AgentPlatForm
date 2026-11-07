import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearConnectorRequests, connectorScope, loadConnectorRequests, saveConnectorRequests } from './connector-requests';

describe('connector recovery identities', () => {
  beforeEach(() => vi.stubGlobal('sessionStorage', {
    getItem(key: string) { return (this as Record<string, unknown>)[key] ?? null; },
    setItem(key: string, value: string) { (this as Record<string, unknown>)[key] = value; },
    removeItem(key: string) { delete (this as Record<string, unknown>)[key]; },
  }));
  afterEach(() => vi.unstubAllGlobals());
  it('stores only request IDs, isolates users and clears on logout', () => {
    const one = connectorScope('alice', 'space-a'); const other = connectorScope('bob', 'space-b');
    const id = '0199e000-0000-7000-8000-000000000001';
    expect(saveConnectorRequests(one, { credentialRequest: id, secret: 'never-save' } as never)).toBe(true);
    expect(sessionStorage.getItem(one)).not.toContain('never-save');
    expect(loadConnectorRequests(one).credentialRequest).toBe(id); expect(loadConnectorRequests(other)).toEqual({});
    clearConnectorRequests(); expect(loadConnectorRequests(one)).toEqual({});
  });
  it('rejects malformed identities rather than retrying them', () => {
    const scope = connectorScope('alice', 'space-a');
    sessionStorage.setItem(scope, JSON.stringify({ probeRequest: 'invalid' }));
    expect(loadConnectorRequests(scope)).toEqual({}); expect(sessionStorage.getItem(scope)).toBeNull();
  });
});
