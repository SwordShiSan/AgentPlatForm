import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { bindingScope, clearBindingRequests, loadBindingRequest, saveBindingRequest } from './binding-requests';

describe('capability binding recovery', () => {
  beforeEach(() => vi.stubGlobal('sessionStorage', {
    getItem(key: string) { return (this as Record<string, unknown>)[key] ?? null; },
    setItem(key: string, value: string) { (this as Record<string, unknown>)[key] = value; },
    removeItem(key: string) { delete (this as Record<string, unknown>)[key]; },
  }));
  afterEach(() => vi.unstubAllGlobals());
  it('restores only the frozen selection for the same account and space', () => {
    const first = bindingScope('a', 's'); const other = bindingScope('b', 's');
    const request = { requestId: '0199e000-0000-7000-8000-000000000001', revision: 2,
      body: { inheritPersonal: false, selections: [{ capabilityId: '0199e000-0000-7000-8000-000000000002', versionId: '0199e000-0000-7000-8000-000000000003' }] } };
    expect(saveBindingRequest(first, request)).toBe(true); expect(loadBindingRequest(first)).toEqual(request);
    expect(loadBindingRequest(other)).toBeUndefined(); clearBindingRequests(); expect(loadBindingRequest(first)).toBeUndefined();
  });
  it('does not restore malformed or oversized requests', () => {
    const scope = bindingScope('a', 's'); sessionStorage.setItem(scope, JSON.stringify({ requestId: 'bad' }));
    expect(loadBindingRequest(scope)).toBeUndefined(); expect(sessionStorage.getItem(scope)).toBeNull();
  });
});
