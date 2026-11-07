import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearSkillImports, loadSkillImport, saveSkillImport, skillImportScope } from './skill-import-requests';

describe('Skill import identities', () => {
  beforeEach(() => vi.stubGlobal('sessionStorage', {
    getItem(key: string) { return (this as Record<string, unknown>)[key] ?? null; },
    setItem(key: string, value: string) { (this as Record<string, unknown>)[key] = value; },
    removeItem(key: string) { delete (this as Record<string, unknown>)[key]; },
  }));
  afterEach(() => vi.unstubAllGlobals());
  it('isolates account and space, then clears identities on logout', () => {
    const first = skillImportScope('alice', 'space-a');
    const other = skillImportScope('bob', 'space-b');
    expect(saveSkillImport(first, { requestId: '0199e000-0000-7000-8000-000000000001',
      completionId: '0199e000-0000-7000-8000-000000000002', body: { sizeBytes: 3, sha256: 'a'.repeat(64) } })).toBe(true);
    expect(loadSkillImport(first)?.body.sizeBytes).toBe(3); expect(loadSkillImport(other)).toBeUndefined();
    clearSkillImports(); expect(loadSkillImport(first)).toBeUndefined();
  });
  it('rejects malformed stored identities instead of replaying them', () => {
    const scope = skillImportScope('alice', 'space-a');
    sessionStorage.setItem(scope, JSON.stringify({ requestId: 'wrong', body: { sizeBytes: 3 } }));
    expect(loadSkillImport(scope)).toBeUndefined(); expect(sessionStorage.getItem(scope)).toBeNull();
  });
});
