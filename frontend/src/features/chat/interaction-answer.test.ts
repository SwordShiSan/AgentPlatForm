import { beforeEach, expect, it, vi } from 'vitest';
import { effectScope } from 'vue';
import { useInteractionAnswer } from './use-interaction-answer';
const mocks=vi.hoisted(()=>({GET:vi.fn(),POST:vi.fn()}));
vi.mock('../../shared/api/client', () => ({ api:mocks }));
vi.mock('../../shared/api/session', () => ({ setCsrf: vi.fn() }));
const interaction = { interactionId: '0199db83-0000-7000-8000-000000000001', taskId: '0199db83-0000-7000-8000-000000000002', kind: 'QUESTION' as const,
  optional: false, version: 1, expiresAt: '2026-10-27T00:00:00Z', question: 'Q', plan: null, options: [] };
beforeEach(() => { vi.clearAllMocks(); mocks.GET.mockResolvedValue({ data: { csrfToken: 'fixture' } }); });
it('a response loss retries the exact answer and key while preserving the UI input', async () => {
  mocks.POST.mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce({ data: {} });
  const refresh = vi.fn(); const scope = effectScope(); const state = scope.run(() => useInteractionAnswer(() => interaction, refresh))!;
  const body = { expectedVersion: 1, decision: 'ANSWER' as const, text: '原回答😀', optionIds: [] };
  await state.send(body); expect(state.uncertain.value).toBe(true);
  body.text = '修改后的回答'; await state.send(body);
  const requests = mocks.POST.mock.calls;
  expect(requests[1]?.[1]?.body).toEqual({ expectedVersion: 1, decision: 'ANSWER', text: '原回答😀', optionIds: [] });
  expect(requests[1]?.[1]?.params?.header?.['Idempotency-Key']).toBe(requests[0]?.[1]?.params?.header?.['Idempotency-Key']);
  expect(state.uncertain.value).toBe(false); expect(refresh).toHaveBeenCalledTimes(2); scope.stop();
});
