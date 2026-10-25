import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearEnvironmentRequests, environmentRequestScope, loadEnvironmentRequest, saveEnvironmentRequest } from './environment-requests';

describe('environment request identity across refresh and account changes', () => {
  const pending = { key: '0199e000-0000-7000-8000-000000000001', version: 0, action: 'PREPARE' as const };
  beforeEach(() => vi.stubGlobal('sessionStorage', {
    getItem(key: string) { return (this as Record<string, unknown>)[key] ?? null; },
    setItem(key: string, value: string) { (this as Record<string, unknown>)[key] = value; },
    removeItem(key: string) { delete (this as Record<string, unknown>)[key]; },
  }));
  afterEach(() => vi.unstubAllGlobals());
  it('preserves the exact request identity without crossing account, space or workspace', () => {
    const scope = environmentRequestScope('alice', 'space', 'workspace');
    saveEnvironmentRequest(scope, pending);
    expect(loadEnvironmentRequest(scope)).toEqual(pending);
    for (const other of [['bob', 'space', 'workspace'], ['alice', 'other', 'workspace'], ['alice', 'space', 'other']])
      expect(loadEnvironmentRequest(environmentRequestScope(other[0]!, other[1]!, other[2]!))).toBeUndefined();
  });
  it('ignores corrupt, oversized and unsafe cached requests', () => {
    const scope = environmentRequestScope('alice', 'space', 'workspace');
    for (const value of ['broken', 'x'.repeat(513), JSON.stringify({ ...pending, action: 'SHELL' }), JSON.stringify({ ...pending, version: -1 }), JSON.stringify({ ...pending, key: '-'.repeat(36) })]) {
      sessionStorage.setItem(scope, value); expect(loadEnvironmentRequest(scope)).toBeUndefined();
    }
  });
  it('retains the exact lifecycle action when confirming a lost response', () => {
    const scope = environmentRequestScope('alice', 'space', 'workspace');
    for (const action of ['SLEEP', 'WAKE', 'DELETE'] as const) {
      saveEnvironmentRequest(scope, { ...pending, version: 7, action });
      expect(loadEnvironmentRequest(scope)).toEqual({ ...pending, version: 7, action });
    }
  });
  it('does not retain unrecognized data in request metadata', () => {
    const scope = environmentRequestScope('alice', 'space', 'workspace');
    sessionStorage.setItem(scope, JSON.stringify({ ...pending, unrelated: 'discard' }));
    expect(loadEnvironmentRequest(scope)).toEqual(pending);
  });
  it('logout clears all environment requests while retaining unrelated UI preferences', () => {
    saveEnvironmentRequest(environmentRequestScope('alice', 'space', 'workspace'), pending);
    saveEnvironmentRequest(environmentRequestScope('bob', 'space', 'workspace'), pending);
    sessionStorage.setItem('theme', 'dark'); clearEnvironmentRequests();
    expect(loadEnvironmentRequest(environmentRequestScope('alice', 'space', 'workspace'))).toBeUndefined();
    expect(loadEnvironmentRequest(environmentRequestScope('bob', 'space', 'workspace'))).toBeUndefined();
    expect(sessionStorage.getItem('theme')).toBe('dark');
  });
});
