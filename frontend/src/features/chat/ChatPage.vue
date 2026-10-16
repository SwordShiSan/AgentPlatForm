<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { api } from '../../shared/api/client';
import { setCsrf } from '../../shared/api/session';
import { applyChatEvent, type ChatSnapshot } from './stream-state';
import type { PlatformEvent } from '../../shared/api/generated/events';

const route = useRoute(); const router = useRouter(); const cache = useQueryClient();
const draft = ref(''); const error = ref(''); const busy = ref(false);
const pending = ref<{ key: string; text: string; model: string; conversation: string }>();
const selected = computed(() => typeof route.params.id === 'string' ? route.params.id : '');
const me = useQuery({ queryKey: ['identity', 'me'], queryFn: async () => (await api.GET('/api/v1/me')).data! });
const actor = computed(() => me.data.value?.id ?? '');
const listKey = computed(() => ['chat', actor.value, 'conversations']);
const snapshotKey = computed(() => ['chat', actor.value, 'snapshot', selected.value]);
const models = useQuery({ queryKey: computed(() => ['chat', actor.value, 'models']), enabled: computed(() => !!actor.value), queryFn: async () => (await api.GET('/api/v1/models')).data! });
const conversations = useQuery({ queryKey: listKey, enabled: computed(() => !!actor.value), queryFn: async () => (await api.GET('/api/v1/conversations', { params: { query: { limit: 100 } } })).data! });
const snapshot = useQuery({ queryKey: snapshotKey, enabled: computed(() => !!actor.value && !!selected.value), queryFn: async () => (await api.GET('/api/v1/conversations/{id}/snapshot', { params: { path: { id: selected.value } } })).data! });
const lastTask = computed(() => snapshot.data.value?.tasks.at(-1));
const running = computed(() => snapshot.data.value?.tasks.some(item => ['QUEUED', 'RUNNING'].includes(item.status)) ?? false);
const usage = useQuery({ queryKey: computed(() => ['chat', actor.value, 'usage', lastTask.value?.taskId]), enabled: computed(() => !!lastTask.value && !running.value), queryFn: async () => (await api.GET('/api/v1/tasks/{id}/usage', { params: { path: { id: lastTask.value!.taskId } } })).data! });
const usedTokens = computed(() => {
  const values = usage.data.value?.items;
  if (!values?.length) return undefined;
  if (values.some(value => value.inputTokens === null || value.outputTokens === null)) return null;
  return values.reduce((sum, value) => sum + (value.inputTokens ?? 0) + (value.outputTokens ?? 0), 0);
});
const reason = computed(() => {
  switch (lastTask.value?.reasonCode) {
    case 'CONTEXT_REQUIRED_TOO_LARGE': return '历史内容超出上下文额度，请新建对话。';
    case 'CREDENTIAL_REVOKED': return '模型凭据已撤销，请联系运维。';
    case 'PROVIDER_TRUNCATED': return '模型回复未完整结束，已保留收到的内容。';
    case 'TASK_CANCEL_REQUESTED': return '回复已取消。';
    case null: case undefined: return '';
    default: return '回复未能完成，已保留现有记录。';
  }
});
const labels: Record<string, string> = { QUEUED: '排队中', RUNNING: '正在回复', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消', BLOCKED: '需要处理' };
let stream: EventSource | undefined; let reconnect: ReturnType<typeof setTimeout> | undefined;
let mounted = true; let createKey: string | undefined;

async function csrf() {
  const value = (await api.GET('/api/v1/auth/csrf')).data!;
  setCsrf(value.csrfToken); return value.csrfToken;
}
function stopStream() { stream?.close(); stream = undefined; if (reconnect) clearTimeout(reconnect); reconnect = undefined; }
function connect() {
  if (!mounted || stream || !selected.value || !snapshot.data.value) return;
  const conversation = selected.value; const key = [...snapshotKey.value];
  const source = new EventSource(`/api/v1/conversations/${encodeURIComponent(conversation)}/events?after=${snapshot.data.value.cursor}`);
  stream = source;
  source.addEventListener('platform_event', event => {
    if (!mounted || conversation !== selected.value) return;
    try {
      const body = JSON.parse((event as MessageEvent<string>).data) as PlatformEvent;
      if (body.conversationId !== conversation) throw new Error('Foreign event');
      cache.setQueryData<ChatSnapshot>(key, previous => previous ? applyChatEvent(previous, body) : previous);
      if (body.type === 'usage.updated') void cache.invalidateQueries({ queryKey: ['chat', actor.value, 'usage'] });
    } catch { stopStream(); void snapshot.refetch().then(() => { if (mounted) connect(); }); }
  });
  source.onerror = () => {
    stopStream();
    reconnect = setTimeout(async () => {
      if (!mounted || conversation !== selected.value) return;
      const result = await snapshot.refetch();
      if (!result.isError) connect(); else error.value = '连接暂时不可用，请刷新重试。';
    }, 1000);
  };
}
watch(() => snapshot.data.value, connect);
watch(selected, () => { stopStream(); draft.value = ''; pending.value = undefined; error.value = ''; });
onBeforeUnmount(() => { mounted = false; stopStream(); draft.value = ''; pending.value = undefined; });

async function create() {
  if (busy.value) return; busy.value = true; error.value = ''; createKey ??= crypto.randomUUID();
  try {
    const token = await csrf();
    const value = (await api.POST('/api/v1/conversations', { params: { header: { 'Idempotency-Key': createKey, 'X-CSRF-Token': token } }, body: { title: '新对话' } })).data!;
    createKey = undefined; await cache.invalidateQueries({ queryKey: listKey.value }); await router.push(`/app/chat/${value.id}`);
  } catch { error.value = '创建暂未确认，可以重试；将复用同一请求标识。'; }
  finally { busy.value = false; }
}
async function moreConversations() {
  const cursor = conversations.data.value?.nextCursor; if (!cursor || busy.value) return;
  busy.value = true;
  try {
    const next = (await api.GET('/api/v1/conversations', { params: { query: { cursor, limit: 100 } } })).data!;
    cache.setQueryData(listKey.value, { items: [...(conversations.data.value?.items ?? []), ...next.items], nextCursor: next.nextCursor });
  } catch { error.value = '暂时无法读取更多对话，请重试。'; }
  finally { busy.value = false; }
}
async function submit() {
  if (busy.value || (!pending.value && (!draft.value.trim() || running.value))) return;
  const model = models.data.value?.items[0]; if (!model || !selected.value) return;
  pending.value ??= { key: crypto.randomUUID(), text: draft.value, model: model.id, conversation: selected.value };
  const value = pending.value; busy.value = true; error.value = '';
  try {
    const token = await csrf();
    await api.POST('/api/v1/conversations/{id}/tasks', { params: { path: { id: value.conversation }, header: { 'Idempotency-Key': value.key, 'X-CSRF-Token': token } }, body: { input: { text: value.text, attachments: [] }, options: { modelId: value.model, planMode: false, permissionMode: 'RISK_BASED' } } });
    pending.value = undefined; draft.value = ''; stopStream(); await snapshot.refetch(); connect();
  } catch { error.value = '提交暂未确认。重试会复用原消息和请求标识，不重复创建任务。'; }
  finally { busy.value = false; }
}
async function cancel() {
  const task = lastTask.value; if (!task || busy.value) return; busy.value = true;
  try {
    const token = await csrf();
    await api.POST('/api/v1/tasks/{id}/cancel', { params: { path: { id: task.taskId }, header: { 'Idempotency-Key': crypto.randomUUID(), 'X-CSRF-Token': token } }, body: {} });
    await snapshot.refetch();
  } catch { error.value = '取消暂未确认，请稍后重试。'; }
  finally { busy.value = false; }
}
</script>

<template>
  <main id="main-content" class="chat-layout" tabindex="-1">
    <aside class="chat-sidebar" aria-label="对话导航">
      <p class="eyebrow">个人空间</p>
      <button :disabled="busy || !models.data.value?.items.length" @click="create">新建对话</button>
      <nav aria-label="历史对话"><RouterLink v-for="item in conversations.data.value?.items" :key="item.id" :to="`/app/chat/${item.id}`" :aria-current="selected === item.id ? 'page' : undefined">{{ item.title }}</RouterLink></nav>
      <button v-if="conversations.data.value?.nextCursor" :disabled="busy" @click="moreConversations">更多对话</button>
      <RouterLink to="/app/settings/account">账号信息</RouterLink>
    </aside>
    <section class="chat-content" aria-label="普通聊天">
      <header><h1>普通聊天</h1><p class="intro">{{ models.data.value?.items[0]?.name ?? '暂无可用模型，请联系运维配置凭据。' }}</p></header>
      <p v-if="!selected" class="intro">新建或选择一个对话开始。</p>
      <p v-if="snapshot.isError.value" role="alert">对话暂时不可用。</p>
      <div class="chat-messages" aria-label="消息历史" :aria-busy="snapshot.isFetching.value">
        <article v-for="message in snapshot.data.value?.messages" :key="message.id" :class="['chat-message', message.role.toLowerCase()]">
          <p class="eyebrow">{{ message.role === 'USER' ? '你' : '助手' }}<span v-if="message.status === 'PARTIAL'"> · 回复未完成</span></p>
          <p v-for="block in message.blocks" :key="block.blockId" class="chat-text">{{ block.text || (message.status === 'STREAMING' ? '等待回复…' : '未产生回复') }}</p>
        </article>
      </div>
      <p v-if="lastTask" role="status">{{ labels[lastTask.status] }}{{ lastTask.cancelRequested && running ? ' · 正在取消' : '' }}<span v-if="reason"> · {{ reason }}</span></p>
      <p v-if="usedTokens !== undefined" class="intro">{{ usedTokens === null ? '用量待对账，未将未知用量记为零。' : `本次使用 ${usedTokens} tokens` }}</p>
      <p v-if="error" role="alert" class="identity-error">{{ error }}</p>
      <form v-if="selected" class="chat-composer" @submit.prevent="submit">
        <label for="chat-input">消息</label>
        <textarea id="chat-input" v-model="draft" rows="3" :disabled="busy || !!pending" placeholder="输入文字，按 Ctrl + Enter 发送" @keydown.ctrl.enter.prevent="submit" @keydown.meta.enter.prevent="submit" />
        <div class="chat-actions"><button type="submit" :disabled="busy || (!pending && (running || !draft.trim())) || !models.data.value?.items.length">{{ pending ? '重试同一条消息' : '发送' }}</button><button v-if="running" type="button" :disabled="busy || lastTask?.cancelRequested" @click="cancel">取消回复</button></div>
      </form>
    </section>
  </main>
</template>
