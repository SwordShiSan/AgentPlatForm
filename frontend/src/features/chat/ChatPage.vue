<script setup lang="ts">
import ModelSettings from './ModelSettings.vue';
import { useChatDrafts, type PendingSubmission } from './drafts';
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { ApiError } from '../../shared/api/request';
import { api } from '../../shared/api/client';
import { setCsrf } from '../../shared/api/session';
import { applyChatEvent, type ChatSnapshot } from './stream-state';
import type { PlatformEvent } from '../../shared/api/generated/events';

const route = useRoute(); const router = useRouter(); const cache = useQueryClient();
const draft = ref(''); const error = ref(''); const busy = ref(false);
const pending = ref<PendingSubmission>();
const selectedModel = ref(''); const source = ref<'PLATFORM' | 'USER'>('PLATFORM'); const drafts = useChatDrafts();
const selected = computed(() => typeof route.params.id === 'string' ? route.params.id : '');
const me = useQuery({ queryKey: ['identity', 'me'], queryFn: async () => (await api.GET('/api/v1/me')).data! });
const actor = computed(() => me.data.value?.id ?? '');
const space = computed(() => me.data.value?.spaces.find(item => item.type === 'PERSONAL')?.id ?? '');
const effective = useQuery({ queryKey: computed(() => ['chat', actor.value, 'effective-config']), enabled: computed(() => !!actor.value), queryFn: async () => (await api.GET('/api/v1/effective-config')).data! });
const preferences = useQuery({ queryKey: computed(() => ['chat', actor.value, 'preferences']), enabled: computed(() => !!actor.value), queryFn: async () => (await api.GET('/api/v1/me/preferences')).data! });

const listKey = computed(() => ['chat', actor.value, 'conversations']);
const snapshotKey = computed(() => ['chat', actor.value, 'snapshot', selected.value]);
const models = useQuery({ queryKey: computed(() => ['chat', actor.value, 'models']), enabled: computed(() => !!actor.value), queryFn: async () => (await api.GET('/api/v1/models')).data! });
const conversations = useQuery({ queryKey: listKey, enabled: computed(() => !!actor.value), queryFn: async () => (await api.GET('/api/v1/conversations', { params: { query: { limit: 100 } } })).data! });
const snapshot = useQuery({ queryKey: snapshotKey, enabled: computed(() => !!actor.value && !!selected.value), queryFn: async () => (await api.GET('/api/v1/conversations/{id}/snapshot', { params: { path: { id: selected.value } } })).data! });
const lastTask = computed(() => snapshot.data.value?.tasks.at(-1));
const config = useQuery({ queryKey: computed(() => ['chat', actor.value, 'configuration', lastTask.value?.taskId]), enabled: computed(() => !!lastTask.value), queryFn: async () => (await api.GET('/api/v1/tasks/{id}/configuration', { params: { path: { id: lastTask.value!.taskId } } })).data! });
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
let mounted = true;

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
let restoring = false;
function restoreDraft() {
  if (!actor.value || !space.value) return;
  restoring = true;
  const value = drafts.load(actor.value, space.value, selected.value);
  draft.value = value.text; pending.value = value.pending; selectedModel.value = value.model || effective.data.value?.values.modelId || ''; source.value = value.model ? value.source : effective.data.value?.values.credentialSource || 'PLATFORM';
  restoring = false;
}
watch([actor, space, selected], () => { stopStream(); error.value = ''; restoreDraft(); }, { immediate: true });
watch(() => effective.data.value, () => { if (!selectedModel.value) restoreDraft(); });
watch([draft, selectedModel, source, pending], () => { if (mounted && !restoring) drafts.save(actor.value, space.value, selected.value, { text: draft.value, model: selectedModel.value, source: source.value, ...(pending.value ? { pending: pending.value } : {}) }); }, { deep: true, flush: 'sync' });
onBeforeUnmount(() => { mounted = false; stopStream(); });

async function create() {
  if (busy.value) return;
  await router.push('/app/chat');
  restoreDraft();
}
async function saveDefaults() {
  if (!preferences.data.value) return;
  busy.value = true;
  try { const token = await csrf(); await api.PATCH('/api/v1/me/preferences', { params: { header: { 'If-Match': `"${preferences.data.value.version}"`, 'X-CSRF-Token': token } }, body: { defaultModelId: selectedModel.value, defaultCredentialSource: source.value } }); await preferences.refetch(); await effective.refetch(); error.value = '今后的新草稿将使用此默认配置；已提交任务保持原配置。'; }
  catch { error.value = '默认配置保存未确认，可能已被其他页面修改，请核对后重试。'; await preferences.refetch(); }
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
  const model = models.data.value?.items.find(item => item.id === selectedModel.value); if (!model) { error.value = '所选模型或凭据不可用，请检查配置。'; return; }
  pending.value ??= { key: crypto.randomUUID(), text: draft.value, model: model.id, source: source.value, conversation: selected.value };
  const value = pending.value; busy.value = true; error.value = '';
  try {
    const token = await csrf();
    const body = { input: { text: value.text, attachments: [] }, options: { modelId: value.model, credentialSource: value.source, planMode: false as const, permissionMode: 'RISK_BASED' as const } };
    let conversation = value.conversation;
    if (conversation) await api.POST('/api/v1/conversations/{id}/tasks', { params: { path: { id: conversation }, header: { 'Idempotency-Key': value.key, 'X-CSRF-Token': token } }, body });
    else { const accepted = (await api.POST('/api/v1/conversations', { params: { header: { 'Idempotency-Key': value.key, 'X-CSRF-Token': token } }, body: { title: '新对话', initialTask: body } })).data!; conversation = accepted.id; }
    pending.value = undefined; draft.value = ''; stopStream(); await cache.invalidateQueries({ queryKey: listKey.value });
    if (!value.conversation) await router.push(`/app/chat/${conversation}`);
    await snapshot.refetch(); await config.refetch(); connect();
  } catch (failure) {
    if (failure instanceof ApiError && [400, 403, 404, 422, 429].includes(failure.problem.status)) { pending.value = undefined; error.value = `提交被拒绝（${failure.problem.code}），输入已保留，请调整配置或额度。`; }
    else error.value = '提交暂未确认。重试会复用原消息和请求标识，不重复创建任务。';
  }
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
      <header><h1>普通聊天</h1><p class="intro">{{ models.data.value?.items.find(item => item.id === selectedModel)?.name ?? '请选择模型' }}</p></header>
      <p v-if="!selected" class="intro">新对话草稿，发送首条消息后保存。</p>
      <p v-if="snapshot.isError.value" role="alert">对话暂时不可用。</p>
      <div class="chat-messages" aria-label="消息历史" :aria-busy="snapshot.isFetching.value">
        <article v-for="message in snapshot.data.value?.messages" :key="message.id" :class="['chat-message', message.role.toLowerCase()]">
          <p class="eyebrow">{{ message.role === 'USER' ? '你' : '助手' }}<span v-if="message.status === 'PARTIAL'"> · 回复未完成</span></p>
          <p v-for="block in message.blocks" :key="block.blockId" class="chat-text">{{ block.text || (message.status === 'STREAMING' ? '等待回复…' : '未产生回复') }}</p>
          <p v-if="!message.blocks.length" class="chat-text">暂无可显示的文本内容。</p>
        </article>
      </div>
      <p v-if="lastTask" role="status">{{ labels[lastTask.status] }}{{ lastTask.cancelRequested && running ? ' · 正在取消' : '' }}<span v-if="reason"> · {{ reason }}</span></p>
      <p v-if="usedTokens !== undefined" class="intro">{{ usedTokens === null ? '用量待对账，未将未知用量记为零。' : `本次使用 ${usedTokens} tokens` }}</p>
      <p v-if="error" role="alert" class="identity-error">{{ error }}</p>
      <form class="chat-composer" @submit.prevent="submit">
        <div class="chat-options">
          <label for="chat-model">模型</label><select id="chat-model" v-model="selectedModel" :disabled="busy || !!pending"><option v-for="item in models.data.value?.items" :key="item.id" :value="item.id">{{ item.name }}</option></select>
          <label for="chat-source">凭据来源</label><select id="chat-source" v-model="source" :disabled="busy || !!pending"><option value="PLATFORM">平台密钥</option><option value="USER">个人密钥</option></select>
          <button type="button" :disabled="busy || !!pending || !selectedModel" @click="saveDefaults">设为我的默认</button>
        </div>
        <p class="intro">选择仅影响下次发送。{{ effective.data.value?.sources.modelId === 'USER' ? '默认模型来自个人偏好。' : '默认模型来自平台。' }}</p>
        <label for="chat-input">消息</label>
        <textarea id="chat-input" v-model="draft" rows="3" :disabled="busy || !!pending" placeholder="输入文字，按 Ctrl + Enter 发送" @keydown.ctrl.enter.prevent="submit" @keydown.meta.enter.prevent="submit" />
        <div class="chat-actions"><button type="submit" :disabled="busy || (!pending && (running || !draft.trim())) || !selectedModel">{{ pending ? '重试同一条消息' : '发送' }}</button><button v-if="running" type="button" :disabled="busy || lastTask?.cancelRequested" @click="cancel">取消回复</button></div>
      </form>
      <details v-if="config.data.value"><summary>当前任务配置</summary><p>模型：{{ config.data.value.values.modelName }}；来源：{{ config.data.value.values.credentialSource }}</p><p class="chat-text">快照 {{ config.data.value.configSnapshotId }} · {{ config.data.value.configHash }}</p><p v-if="!config.data.value.restrictions.credentialActive">此任务凭据已撤销。</p></details>
      <ModelSettings v-if="actor" :actor="actor" />
    </section>
  </main>
</template>
