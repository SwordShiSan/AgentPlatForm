import { onScopeDispose, ref } from 'vue';
import type { components } from '../../shared/api/generated/http';
import { api } from '../../shared/api/client';
import { ApiError } from '../../shared/api/request';
import { newRequestId } from '../../shared/api/request-id';
import { setCsrf } from '../../shared/api/session';

type Pending = components['schemas']['PendingInteraction'];
type Answer = components['schemas']['AnswerInteraction'];

/** A lost response keeps the exact body and idempotency key until canonical state is reloaded. */
export function useInteractionAnswer(interaction: () => Pending, refresh: () => void) {
  const busy = ref(false); const error = ref(''); const uncertain = ref(false);
  const controller=new AbortController();let disposed=false;
  onScopeDispose(()=>{disposed=true;controller.abort();});
  let original: { key: string; body: Answer } | undefined;
  async function send(body: Answer) {
    if (busy.value||disposed) return;
    original ??= { key: newRequestId(), body: structuredClone(body) };
    busy.value = true; error.value = '';
    try {
      const token = (await api.GET('/api/v1/auth/csrf',{signal:controller.signal})).data!.csrfToken; setCsrf(token);
      const current = interaction();
      await api.POST('/api/v1/tasks/{id}/interactions/{interactionId}/answer', {signal:controller.signal,
        params: { path: { id: current.taskId, interactionId: current.interactionId },
          header: { 'Idempotency-Key': original.key, 'X-CSRF-Token': token } }, body: original.body,
      });
      uncertain.value = false; original = undefined; if(!disposed)refresh();
    } catch (failure) {
      if(disposed)return;
      if (failure instanceof ApiError && [400, 403, 404, 409, 412, 422].includes(failure.problem.status)) {
        error.value = failure.problem.code === 'CONTEXT_REQUIRED_TOO_LARGE'
          ? '内容已保留，但超出当前模型上下文，不能续接。请缩短修改要求或计划。'
          : failure.problem.status === 412 ? '此交互已更新，正在读取最新计划和状态。'
          : '回答未被接受，请核对当前交互状态。';
        original = undefined; uncertain.value = false;
      } else { uncertain.value = true; error.value = '回答暂未确认。可核对状态，或使用原标识重试同一回答。'; }
      refresh();
    } finally { busy.value = false; }
  }
  function retry() { if (original) void send(original.body); }
  return { busy, error, uncertain, send, retry };
}
