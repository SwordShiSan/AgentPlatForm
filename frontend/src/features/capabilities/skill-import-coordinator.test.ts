import { beforeEach, describe, expect, it, vi } from 'vitest';

const request = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn() }));
const transfer = vi.hoisted(() => ({ fileHash: vi.fn(), transferSkillArchive: vi.fn() }));
vi.mock('../../shared/api/client', () => ({ api: request }));
vi.mock('../workspace/file-transfers', () => transfer);
import { continueSkillImport, skillImportStatus, type PendingSkillImport } from './skill-import-coordinator';

const pending = (): PendingSkillImport => ({ requestId: 'create-request', completionId: 'complete-request',
  body: { sizeBytes: 3, sha256: 'a'.repeat(64) } });
const signal = () => new AbortController().signal;
beforeEach(() => { vi.resetAllMocks(); });

describe('Skill import recovery', () => {
  it('finds an accepted original request without writing when its response was lost', async () => {
    request.GET.mockResolvedValueOnce({ data: { id: 'original', state: 'BLOCKED' } });
    const requestState = pending(); const persist = vi.fn();
    const state = await skillImportStatus(requestState, 'space', signal(), persist);
    expect(state.state).toBe('BLOCKED'); expect(requestState.importId).toBe('original');
    expect(persist).toHaveBeenCalledOnce(); expect(request.POST).not.toHaveBeenCalled();
    expect(transfer.transferSkillArchive).not.toHaveBeenCalled();
  });
  it('does not upload or enqueue when the original transfer is unresolved', async () => {
    const requestState = pending(); requestState.importId = 'original';
    request.GET.mockResolvedValueOnce({ data: { csrfToken: 'csrf' } }).mockResolvedValueOnce({ data: { id: 'original', state: 'BLOCKED' } });
    const state = await continueSkillImport(requestState, new File(['abc'], 'skill.zip'), 'space', signal(), vi.fn());
    expect(state.state).toBe('BLOCKED'); expect(request.POST).not.toHaveBeenCalled();
    expect(transfer.transferSkillArchive).not.toHaveBeenCalled();
  });
  it('rejects changed content before transferring', async () => {
    const requestState = pending(); requestState.importId = 'original';
    request.GET.mockResolvedValueOnce({ data: { csrfToken: 'csrf' } }).mockResolvedValueOnce({ data: { id: 'original', state: 'CREATED' } });
    transfer.fileHash.mockResolvedValue('b'.repeat(64));
    await expect(continueSkillImport(requestState, new File(['abc'], 'skill.zip'), 'space', signal(), vi.fn())).rejects.toThrow('不一致');
    expect(transfer.transferSkillArchive).not.toHaveBeenCalled(); expect(request.POST).not.toHaveBeenCalled();
  });
});
