import { beforeEach, describe, expect, it, vi } from 'vitest';
import { clearMutationRequests, loadMutation, mutationScope, persistMutation, readMutation, resolveDirectory, submitMutation, type PendingMutation } from './file-mutation-requests';

const { get, post, remove, setCsrf } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), remove: vi.fn(), setCsrf: vi.fn() }));
vi.mock('../../shared/api/client', () => ({ api: { GET: get, POST: post, DELETE: remove } }));
vi.mock('../../shared/api/session', () => ({ setCsrf }));
const key = '12345678-1234-1234-1234-123456789abc';
const file = '22345678-1234-1234-1234-123456789abc';
const pending: PendingMutation = { kind: 'MOVE', key, fileId: file, ifMatch: '"3"', body: { targetParentId: null, name: '中文😀.txt' } };
beforeEach(() => {
  vi.clearAllMocks();
  const values: Record<string, string> = {};
  Object.defineProperties(values, { setItem: { value: (k: string, v: string) => { values[k] = v; } },
    getItem: { value: (k: string) => values[k] ?? null }, removeItem: { value: (k: string) => { delete values[k]; } } });
  vi.stubGlobal('sessionStorage', values);
});
describe('frozen file lifecycle requests', () => {
  it('isolates identity and restores only the original frozen request', () => {
    const scope = mutationScope('alice', 's', 'w', file);
    expect(persistMutation(scope, pending)).toBe(true);
    expect(loadMutation(scope)).toEqual(pending);
    expect(loadMutation(mutationScope('bob', 's', 'w', file))).toBeUndefined();
    expect(loadMutation(mutationScope('alice', 'other', 'w', file))).toBeUndefined();
    clearMutationRequests(); expect(loadMutation(scope)).toBeUndefined();
  });
  it('rejects malformed request identity and preserves in-memory input when storage is full', () => {
    const scope = mutationScope('alice', 's', 'w', file);
    persistMutation(scope, { ...pending, ifMatch: 'content-sha256' }); expect(loadMutation(scope)).toBeUndefined();
    vi.stubGlobal('sessionStorage', { setItem() { throw new Error('quota'); } });
    expect(persistMutation(scope, pending)).toBe(false); expect(pending.body?.name).toBe('中文😀.txt');
  });
  it('lost responses query the original key and never submit a second mutation', async () => {
    get.mockResolvedValue({ data: { phase: 'UNKNOWN', operation: { id: key, state: 'UNKNOWN' } } });
    await readMutation(pending, 's', new AbortController().signal);
    expect(get.mock.calls[0][1].params.path).toEqual({ id: file, requestId: key }); expect(post).not.toHaveBeenCalled();
  });
  it('explicit continuation retains the key and strips the inactive restore source', async () => {
    get.mockResolvedValue({ data: { csrfToken: 'csrf' } }); post.mockResolvedValue({ data: { id: key } });
    await submitMutation({ ...pending, kind: 'RESTORE', body: { sourceVersionId: key, deletionOperationId: null, targetParentId: null, name: null } }, 's', new AbortController().signal);
    const input = post.mock.calls[0][1]; expect(input.params.header['Idempotency-Key']).toBe(key);
    expect(input.body).toEqual({ sourceVersionId: key, targetParentId: null, name: null });
  });
  it('resolves directory identities by segment and page without matching neighbors', async () => {
    get.mockResolvedValueOnce({ data: { items: [{ id: 'wrong', name: 'folder-other', kind: 'DIRECTORY', collectionState: 'CONFIRMED' }], nextCursor: 'next' } })
      .mockResolvedValueOnce({ data: { items: [{ id: 'dir', name: 'folder', path: 'folder', kind: 'DIRECTORY', collectionState: 'CONFIRMED' }] } })
      .mockResolvedValueOnce({ data: { items: [{ id: 'child', name: '子目录', path: 'folder/子目录', kind: 'DIRECTORY', collectionState: 'CONFIRMED' }] } });
    expect(await resolveDirectory('w', 's', '/folder/子目录/', new AbortController().signal)).toBe('child');
    expect(get.mock.calls[1][1].params.query.cursor).toBe('next');
    expect(get.mock.calls[2][1].params.query.parent).toBe('folder');
    await expect(resolveDirectory('w', 's', 'folder/../escape', new AbortController().signal)).rejects.toThrow('目标目录无效');
  });
});
