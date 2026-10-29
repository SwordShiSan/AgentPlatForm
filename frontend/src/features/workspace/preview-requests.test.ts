import { beforeEach, describe, expect, it, vi } from 'vitest';
import { clearPreviewRequests, pendingPreview, previewScope, savePreview, submitPreview } from './preview-requests';
const { get, post, setCsrf } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), setCsrf: vi.fn() }));
vi.mock('../../shared/api/client', () => ({ api: { GET: get, POST: post } }));
vi.mock('../../shared/api/session', () => ({ setCsrf }));
const version = '12345678-1234-1234-1234-123456789abc';
beforeEach(() => {
  vi.clearAllMocks(); const values: Record<string, string> = {};
  Object.defineProperties(values, { getItem: { value: (key: string) => values[key] ?? null }, setItem: { value: (key: string, value: string) => { values[key] = value; } },
    removeItem: { value: (key: string) => { delete values[key]; } } }); vi.stubGlobal('sessionStorage', values);
});
describe('preview request recovery', () => {
  it('keeps the same opaque identity after response loss or refresh and isolates accounts, spaces, versions and format', () => {
    const scope = previewScope('alice', 's', 'f', version, 'AUTO');
    const request = pendingPreview(scope, { versionId: version, format: 'AUTO' });
    expect(pendingPreview(scope, request.body)).toEqual(request);
    savePreview(scope, { ...request, previewId: version }); expect(pendingPreview(scope, request.body).previewId).toBe(version);
    for (const other of [previewScope('bob', 's', 'f', version, 'AUTO'), previewScope('alice', 'other', 'f', version, 'AUTO'),
      previewScope('alice', 's', 'f', version, 'HTML_STATIC')]) expect(pendingPreview(other, request.body).key).not.toBe(request.key);
    expect(JSON.stringify(sessionStorage)).not.toContain('bodyText'); clearPreviewRequests(); expect(pendingPreview(scope, request.body).key).not.toBe(request.key);
  });
  it('submits only the immutable version/format and original key after a lost response', async () => {
    get.mockResolvedValue({ data: { csrfToken: 'csrf' } }); post.mockRejectedValueOnce(new Error('lost')).mockResolvedValueOnce({ data: { id: version } });
    const request = pendingPreview(previewScope('a', 's', 'f', version, 'AUTO'), { versionId: version, format: 'AUTO' });
    await expect(submitPreview('file', request, 's', new AbortController().signal)).rejects.toThrow('lost');
    await expect(submitPreview('file', request, 's', new AbortController().signal)).resolves.toEqual({ id: version });
    expect(post.mock.calls.map(call => call[1].params.header['Idempotency-Key'])).toEqual([request.key, request.key]);
    expect(post.mock.calls[1][1].body).toEqual({ versionId: version, format: 'AUTO' }); expect(setCsrf).toHaveBeenCalledWith('csrf');
  });
  it('discards corrupt saved metadata without copying arbitrary fields or file content', () => {
    const scope = previewScope('a', 's', 'f', version, 'AUTO'); sessionStorage.setItem(scope, JSON.stringify({ key: 'bad', body: { versionId: version, format: 'AUTO' }, bytes: 'private' }));
    const value = pendingPreview(scope, { versionId: version, format: 'AUTO' }); expect(value.key).not.toBe('bad'); expect(value).not.toHaveProperty('bytes');
  });
});
