import { beforeEach, expect, it, vi } from 'vitest';
import { effectScope, reactive } from 'vue';
import { useInteractionAnswer } from './use-interaction-answer';
import { ApiError } from '../../shared/api/request';
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
it('freezes reactive option selections without cloning a Vue proxy', async () => {
  mocks.POST.mockResolvedValue({ data: {} });
  const scope = effectScope(); const state = scope.run(() => useInteractionAnswer(() => interaction, vi.fn()))!;
  const selected = reactive(['brief']);
  await state.send({ expectedVersion: 1, decision: 'ANSWER', text: '', optionIds: selected });
  selected.push('changed');
  expect(mocks.POST.mock.calls[0]?.[1]?.body.optionIds).toEqual(['brief']);
  expect(state.error.value).toBe(''); scope.stop();
});
it('retries only the original once approval after a lost response, never changed parameters', async () => {
  mocks.POST.mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce({ data: {} });
  const approval = { ...interaction, kind: 'TOOL_APPROVAL' as const, optional: false as const, question: null, approval: {
    operationId: interaction.interactionId, toolName: 'platform_approval_fixture', summary: '当前调用', reasonCode: 'CONFIRMATION_REQUIRED',
    workspaceId: null, target: null, targetVersion: null, callHash: 'a'.repeat(64), stale: false,
    allowedActions: ['ALLOW_ONCE', 'DENY'] as ('ALLOW_ONCE' | 'DENY')[], permissionVersion: 1, policyVersion: 'task-directory-policy-v1' } };
  const scope = effectScope(); const state = scope.run(() => useInteractionAnswer(() => approval, vi.fn()))!;
  await state.send({ expectedVersion: 1, decision: 'ALLOW_ONCE' });
  expect(state.uncertain.value).toBe(true);
  await state.send({ expectedVersion: 1, decision: 'DENY' });
  expect(mocks.POST.mock.calls[1]?.[1]?.body).toEqual({ expectedVersion: 1, decision: 'ALLOW_ONCE', optionIds: [] });
  expect(mocks.POST.mock.calls[1]?.[1]?.params.header['Idempotency-Key']).toBe(mocks.POST.mock.calls[0]?.[1]?.params.header['Idempotency-Key']);
  expect(mocks.POST.mock.calls[1]?.[1]?.body).not.toHaveProperty('arguments');
  expect(state.uncertain.value).toBe(false); scope.stop();
});
it('does not retain an approval retry after a canonical version conflict', async () => {
  mocks.POST.mockRejectedValue(new ApiError({ type: 'urn:problem', title: 'Changed', status: 412,
    code: 'APPROVAL_STALE', traceId: '', retryable: false }));
  const refresh = vi.fn(); const scope = effectScope(); const state = scope.run(() => useInteractionAnswer(() => interaction, refresh))!;
  await state.send({ expectedVersion: 1, decision: 'ALLOW_ONCE' });
  expect(state.uncertain.value).toBe(false); expect(refresh).toHaveBeenCalledOnce();
  state.retry(); expect(mocks.POST).toHaveBeenCalledOnce(); scope.stop();
});
