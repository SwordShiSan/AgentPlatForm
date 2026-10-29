import { beforeEach, describe, expect, it, vi } from 'vitest';
import { decodeText, encodeText, persistDraft, restoreDraft, draftKey, clearEditorDrafts, type EditorDraft } from './editor-session';
const d = (): EditorDraft => ({ fileId: 'file', versionId: 'version', revision: 1, path: 'a.txt', base: '', text: '中文😀', format: { bom: false, eol: '\n' } });
beforeEach(() => {
  const store: Record<string, string> = {};
  vi.stubGlobal('sessionStorage', new Proxy(store, { get(target, key) {
    if (key === 'getItem') return (k: string) => target[k] ?? null;
    if (key === 'setItem') return (k: string, v: string) => { target[k] = v; };
    if (key === 'removeItem') return (k: string) => { delete target[k]; };
    return Reflect.get(target, key);
  } }));
});
describe('versioned editor content and private drafts', () => {
  it('preserves BOM and CRLF for unchanged UTF8 text', () => {
    const bytes = new TextEncoder().encode('\ufeff中文\r\n😀\r\n');
    const decoded = decodeText(bytes.buffer);
    expect(encodeText(decoded.text, decoded.format)).toEqual(bytes);
  });
  it('accepts empty content and exact limit but rejects encoded overflow', () => {
    expect(encodeText('', d().format).length).toBe(0);
    expect(encodeText('a'.repeat(2097152), d().format).length).toBe(2097152);
    expect(() => encodeText('中'.repeat(700000), d().format)).toThrow('2 MiB');
  });
  it('rejects invalid UTF8 and binary', () => {
    expect(() => decodeText(new Uint8Array([255]).buffer)).toThrow();
    expect(() => decodeText(new Uint8Array([0]).buffer)).toThrow();
  });
  it('isolates drafts by identity and clears only editor entries', () => {
    const key = draftKey('a', 's', 'w', 'f'); expect(persistDraft(key, d())).toBe(true);
    expect(restoreDraft(key)?.text).toBe('中文😀'); expect(restoreDraft(draftKey('b', 's', 'w', 'f'))).toBeUndefined();
    sessionStorage.setItem('unrelated', 'kept'); clearEditorDrafts(); expect(restoreDraft(key)).toBeUndefined(); expect(sessionStorage.getItem('unrelated')).toBe('kept');
  });
  it('does not evict existing drafts on total quota exhaustion', () => {
    const first = { ...d(), text: 'a'.repeat(2100000) }; expect(persistDraft('agentplatform:editor:one', first)).toBe(true);
    expect(persistDraft('agentplatform:editor:two', first)).toBe(false); expect(restoreDraft('agentplatform:editor:one')?.text).toBe(first.text);
  });
});
