import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { fileHash, textPreview } from './file-transfers';
import { fileRequestScope, loadFileRequest, saveFileRequest, clearFileRequests } from './file-requests';

describe('bounded file inputs', () => {
  beforeEach(() => vi.stubGlobal('sessionStorage', {
    getItem(key: string) { return (this as Record<string, unknown>)[key] ?? null; },
    setItem(key: string, value: string) { (this as Record<string, unknown>)[key] = value; },
    removeItem(key: string) { delete (this as Record<string, unknown>)[key]; },
  }));
  afterEach(() => vi.unstubAllGlobals());
  it('hashes zero and multi-chunk files incrementally', async () => {
    expect(await fileHash(new Blob())).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
    expect(await fileHash(new Blob(['abc']))).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
    const large = new Blob([new Uint8Array(1048577)]);
    const first = await fileHash(large); expect(first).toMatch(/^[a-f0-9]{64}$/);
    const controller = new AbortController(); controller.abort();
    await expect(fileHash(large, controller.signal)).rejects.toThrow();
  });
  it('never treats binary or invalid UTF8 as safe text', () => {
    expect(textPreview(new TextEncoder().encode('<script>666</script>').buffer)).toContain('<script>');
    expect(() => textPreview(new Uint8Array([255]).buffer)).toThrow();
    expect(() => textPreview(new Uint8Array([0]).buffer)).toThrow();
    expect(() => textPreview(new ArrayBuffer(2097153))).toThrow();
  });
  it('isolates pending identities and never stores file contents', () => {
    const a = fileRequestScope('a', 's', 'w'), b = fileRequestScope('b', 's', 'w');
    saveFileRequest(a, { key: '0199e000-0000-7000-8000-000000000001', body: { path: '中文.txt', bytes: 0, sha256: 'a'.repeat(64) } });
    expect(loadFileRequest(b)).toBeUndefined(); expect(loadFileRequest(a)?.body.path).toBe('中文.txt');
    clearFileRequests(); expect(loadFileRequest(a)).toBeUndefined();
  });
});
