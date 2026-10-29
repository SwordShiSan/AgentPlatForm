import { describe, expect, it, vi, beforeEach } from 'vitest';
const request = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn() }));
vi.mock('../../shared/api/client', () => ({ api: request }));
vi.mock('./file-transfers', () => ({ fileHash: vi.fn(), transferFile: vi.fn() }));
import { bindUploadWorkspace, continueFileUpload } from './file-upload-coordinator';
import type { PendingFileRequest } from './file-requests';
const pending = (): PendingFileRequest => ({ key: 'request', uploadId: 'upload', body: { path: 'a.txt', bytes: 3, sha256: 'a'.repeat(64) } });
beforeEach(() => vi.clearAllMocks());
describe('read-only reconciliation never replays file writes', () => {
  it('reads original unknown operation without CSRF or writes', async () => {
    request.GET.mockResolvedValueOnce({ data: { state: 'UNKNOWN', operationId: 'operation' } }).mockResolvedValueOnce({ data: { id: 'operation', state: 'UNKNOWN' } });
    const result = await continueFileUpload(bindUploadWorkspace(pending(), 'workspace'), undefined, 'space', new AbortController().signal, vi.fn(), undefined, true);
    expect(result?.state).toBe('UNKNOWN'); expect(request.POST).not.toHaveBeenCalled(); expect(request.GET).toHaveBeenCalledTimes(2);
    expect(request.GET.mock.calls[0][0]).toBe('/api/v1/uploads/{id}');
  });
  it('does not automatically recreate an unconfirmed upload identity', async () => {
    const p = pending(); delete p.uploadId;
    expect(await continueFileUpload(p, undefined, 'space', new AbortController().signal, vi.fn(), undefined, true)).toBeUndefined();
    expect(request.GET).not.toHaveBeenCalled(); expect(request.POST).not.toHaveBeenCalled();
  });
  it('does not resend content for a CREATED upload during a status poll', async () => {
    request.GET.mockResolvedValueOnce({ data: { state: 'CREATED' } });
    await continueFileUpload(pending(), undefined, 'space', new AbortController().signal, vi.fn(), undefined, true);
    expect(request.GET).toHaveBeenCalledTimes(1); expect(request.POST).not.toHaveBeenCalled();
  });
});
