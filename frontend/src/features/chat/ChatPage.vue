<script setup lang="ts">
import ModelSettings from './ModelSettings.vue';
import PlanCard from './PlanCard.vue';
import TaskPermissions from './TaskPermissions.vue';
import QuestionCard from './QuestionCard.vue';
import ApprovalCard from './ApprovalCard.vue';
import ToolOperationsPanel from './ToolOperationsPanel.vue';
import RecoveryNotice from './RecoveryNotice.vue';
import AttachmentComposer from './AttachmentComposer.vue';
import WorkspaceFilesPanel from '../workspace/WorkspaceFilesPanel.vue';
import FilePreviewPane from '../workspace/FilePreviewPane.vue';
import type { AttachmentDraft, AttachmentReference } from './attachment-drafts';
import { useChatDrafts, type PendingSubmission, type ConfigurationChoices } from './drafts';
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { ApiError } from '../../shared/api/request';
import { api } from '../../shared/api/client';
import type { components } from '../../shared/api/generated/http';
import { newRequestId } from '../../shared/api/request-id';
import { setCsrf } from '../../shared/api/session';
import { applyChatEvent, SnapshotRequired, type ChatSnapshot } from './stream-state';
import { subscribeReplay, type StreamStatus } from './event-stream';

const route = useRoute(); const router = useRouter(); const cache = useQueryClient();
const planEnabled = import.meta.env.VITE_PLAN_ENABLED === 'true';
const personalConfigEnabled = import.meta.env.VITE_PERSONAL_CONFIG_ENABLED === 'true';
const configurationChoices = ref<ConfigurationChoices>({ model: '', source: '', plan: '', permission: '' });
const planMode = ref(false);
const permissionsEnabled = import.meta.env.VITE_PERMISSIONS_ENABLED === 'true';
const workspaceToolsEnabled = import.meta.env.VITE_WORKSPACE_TOOLS_ENABLED === 'true';
const knowledgeEnabled = import.meta.env.VITE_KNOWLEDGE_ENABLED === 'true';
const permissionMode = ref<'RISK_BASED' | 'FULL'>('RISK_BASED');const workspaceId = ref('');
const draft = ref(''); const error = ref(''); const busy = ref(false);
const attachmentsEnabled = import.meta.env.VITE_WORKSPACE_FILES_ENABLED === 'true';
const attachmentDrafts = ref<AttachmentDraft[]>([]); const attachmentRefs = ref<AttachmentReference[]>([]); const attachmentsProcessing = ref(false);
const attachmentComposer = ref<InstanceType<typeof AttachmentComposer>>();
const sourcePreview = ref<components['schemas']['ChatFileReferenceBlock']>();
const workbenchView = ref<'SPLIT' | 'CHAT' | 'FILES'>('SPLIT'); const workbenchWorkspace = ref('');
const workbenchFile = computed(() => workspaces.data.value?.items.find(item => item.id === (workbenchWorkspace.value || workspaceId.value) && item.state === 'READY'));
const pending = ref<PendingSubmission>();
const selectedModel = ref(''); const source = ref<'PLATFORM' | 'USER'>('PLATFORM'); const drafts = useChatDrafts();
const selected = computed(() => typeof route.params.id === 'string' ? route.params.id : '');
const me = useQuery({ queryKey: ['identity', 'me'], queryFn: async () => (await api.GET('/api/v1/me')).data! });
const actor = computed(() => me.data.value?.id ?? '');
const space = computed(() => me.data.value?.spaces.find(item => item.type === 'PERSONAL')?.id ?? '');
const workspaces = useQuery({ queryKey: computed(() => ['chat', actor.value, space.value, 'workspace-choices']), enabled: computed(() => permissionsEnabled && !!actor.value && !!space.value), queryFn: async () => (await api.GET('/api/v1/workspaces', { params: { query: { limit: 100 } } })).data! });
const effective = useQuery({ queryKey: computed(() => ['chat', actor.value, 'effective-config', personalConfigEnabled ? workspaceId.value : '', personalConfigEnabled ? { ...configurationChoices.value } : null] as const), enabled: computed(() => !!actor.value), queryFn: async ({ queryKey }) => {
  if (!personalConfigEnabled) return (await api.GET('/api/v1/effective-config')).data!;
  const selected=queryKey[4]!;const selectedWorkspace=queryKey[3];
  return (await api.GET('/api/v1/effective-config', { params: { query: {
    ...(selectedWorkspace ? { workspaceId: selectedWorkspace } : {}), ...(selected.model ? { modelId: selected.model } : {}),
    ...(selected.source ? { credentialSource: selected.source } : {}), ...(selected.plan ? { planMode: selected.plan === 'ON' } : {}),
    ...(selected.permission ? { permissionMode: selected.permission } : {}),
  } } })).data!;
} });
const preferences = useQuery({ queryKey: computed(() => ['chat', actor.value, 'preferences']), enabled: computed(() => !!actor.value), queryFn: async () => (await api.GET('/api/v1/me/preferences')).data! });
watch(() => preferences.data.value?.workbenchLayout, value => { if (value) workbenchView.value = value.view; }, { immediate: true });
async function selectWorkbench(view: 'SPLIT' | 'CHAT' | 'FILES') {
  workbenchView.value = view;
  if (!preferences.data.value) return;
  try { const token = await csrf(); await api.PATCH('/api/v1/me/preferences', { body: { workbenchLayout: { view, sidebarWidth: 384 } }, params: { header: { 'If-Match': `"${preferences.data.value.version}"`, 'X-CSRF-Token': token } } }); await preferences.refetch(); }
  catch { error.value = '布局偏好未保存，请核对后重试。'; await preferences.refetch(); }
}
async function referenceFile(file: components['schemas']['FileItem'], modify = false) {
  if (!file.versionId || busy.value || pending.value) return;
  if (modify) {
    workspaceId.value = file.workspaceId; planMode.value = true;if(personalConfigEnabled)configurationChoices.value.plan='ON';
    const request = `请修改工作区文件「${file.path}」，先说明方案；等待我的确认，不自动批准工具。`;
    draft.value = draft.value ? `${draft.value}\n\n${request}` : request;
  }
  await attachmentComposer.value?.addVersion(file.id, file.versionId, file.name);
  workbenchView.value = 'CHAT';
  if (preferences.data.value) try {
    const recent = [{ workspaceId: file.workspaceId, fileId: file.id, versionId: file.versionId }, ...(preferences.data.value.recentFiles ?? []).filter(item => item.fileId !== file.id)].slice(0, 20);
    const token = await csrf(); await api.PATCH('/api/v1/me/preferences', { body: { recentFiles: recent }, params: { header: { 'If-Match': `"${preferences.data.value.version}"`, 'X-CSRF-Token': token } } }); await preferences.refetch();
  } catch { error.value = '文件引用已保留；最近文件偏好未保存，请刷新核对。'; await preferences.refetch(); }
}
const linkedSource = computed(() => {
  const { sourceFile, sourceVersion } = route.query;
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  return typeof sourceFile === 'string' && typeof sourceVersion === 'string' && uuid.test(sourceFile) && uuid.test(sourceVersion)
    ? { fileId: sourceFile, versionId: sourceVersion } : undefined;
});
async function addLinkedSource() {
  if (!linkedSource.value || busy.value || pending.value) return;
  try {
    const file = (await api.GET('/api/v1/files/{id}', { params: { path: { id: linkedSource.value.fileId } } })).data!;
    await referenceFile({ ...file, versionId: linkedSource.value.versionId });
    const query = { ...route.query }; delete query.sourceFile; delete query.sourceVersion; delete query.sourceWorkspace;
    await router.replace({ path: route.path, query });
  } catch { error.value = '无法引用此固定版本，请核对文件权限或版本状态。'; }
}

const listKey = computed(() => ['chat', actor.value, 'conversations']);
const snapshotKey = computed(() => ['chat', actor.value, space.value, 'snapshot', selected.value]);
const models = useQuery({ queryKey: computed(() => ['chat', actor.value, 'models']), enabled: computed(() => !!actor.value), queryFn: async () => (await api.GET('/api/v1/models')).data! });
const modelAvailable = computed(() => personalConfigEnabled ? !!effective.data.value?.canSubmit && !effective.isFetching.value : (models.data.value?.items.some(item => item.id === selectedModel.value) ?? false));
const conversations = useQuery({ queryKey: listKey, enabled: computed(() => !!actor.value), queryFn: async () => (await api.GET('/api/v1/conversations', { params: { query: { limit: 100 } } })).data! });
const snapshot = useQuery({ queryKey: snapshotKey, enabled: computed(() => !!actor.value && !!space.value && !!selected.value), queryFn: async ({ queryKey, signal }) => (await api.GET('/api/v1/conversations/{id}/snapshot', { signal, params: { path: { id: String(queryKey[4]) }, header: { 'X-Space-Id': String(queryKey[2]) } } })).data! });
const quota = useQuery({ queryKey: computed(() => ['chat', actor.value, 'quota']), enabled: computed(() => !!actor.value), queryFn: async () => (await api.GET('/api/v1/me/usage-quota')).data! });
const tokenQuota = computed(() => quota.data.value?.find(item => item.metric === 'TOKENS'));
async function refreshInteractions() { await snapshot.refetch(); await quota.refetch(); }
const lastTask = computed(() => snapshot.data.value?.tasks.at(-1));
const unknownTasks = computed(() => snapshot.data.value?.tasks.filter(task => task.status === 'BLOCKED' && ['EXTERNAL_RESULT_UNKNOWN', 'WORKER_INTERRUPTED', 'AUTHORIZATION_REVOKED'].includes(task.reasonCode ?? '')) ?? []);
const reconciliationTask = computed(() => lastTask.value?.cancelRequested && lastTask.value.status === 'RUNNING' ? lastTask.value : unknownTasks.value.at(-1) ?? lastTask.value);
const reconciliationKey = computed(() => ['chat', actor.value, space.value, 'reconciliation', reconciliationTask.value?.taskId, reconciliationTask.value?.version]);
const reconciliation = useQuery({
  queryKey: reconciliationKey,
  enabled: computed(() => !!actor.value && !!space.value && !!reconciliationTask.value),
  refetchInterval: query => {
    const failure = query.state.error;
    if (failure instanceof ApiError && [401, 403, 404].includes(failure.problem.status)) return false;
    return reconciliationTask.value?.cancelRequested && reconciliationTask.value.status === 'RUNNING' ? 2000 : false;
  },
  queryFn: async ({ queryKey, signal }) => (await api.GET('/api/v1/tasks/{id}/reconciliation', {
    signal, params: { path: { id: String(queryKey[4]) }, header: { 'X-Space-Id': String(queryKey[2]) } },
  })).data!,
});
const reconciliationState = computed(() => reconciliation.data.value
  ? cache.getQueryData<components['schemas']['TaskReconciliation']>(reconciliationKey.value) : undefined);
const stopLabel = computed(() => {
  if (reconciliationState.value?.stopStage === 'UNCONFIRMED') return '停止待确认，请提供诊断编号联系运维；执行槽仍保留。';
  if (reconciliationState.value?.stopStage === 'STOPPING') return '正在停止，请等待执行者及连接退出。';
  return '';
});
const config = useQuery({ queryKey: computed(() => ['chat', actor.value, 'configuration', lastTask.value?.taskId, lastTask.value?.version]), enabled: computed(() => !!lastTask.value), queryFn: async () => (await api.GET('/api/v1/tasks/{id}/configuration', { params: { path: { id: lastTask.value!.taskId } } })).data! });
const contextSummary = useQuery({ queryKey: computed(() => ['chat', actor.value, space.value, 'context-summary', lastTask.value?.taskId, lastTask.value?.version]), enabled: computed(() => knowledgeEnabled && !!lastTask.value), queryFn: async ({ signal }) => (await api.GET('/api/v1/tasks/{id}/context', { signal, params: { path: { id: lastTask.value!.taskId }, header: { 'X-Space-Id': space.value } } })).data! });
const snapshotResolution = computed(() => config.data.value?.values.resolution as { resolverVersion?: string; sources?: Record<string,string>; effectiveHash?: string } | undefined);
const running = computed(() => snapshot.data.value?.tasks.some(item => ['QUEUED', 'RUNNING', 'WAITING_INPUT'].includes(item.status)) ?? false);
const diagnostics = useQuery({ queryKey: computed(() => ['chat', actor.value, space.value, 'diagnostics', lastTask.value?.taskId, lastTask.value?.version]), enabled: computed(() => !!lastTask.value && !running.value), queryFn: async () => (await api.GET('/api/v1/tasks/{id}/diagnostics', { params: { path: { id: lastTask.value!.taskId } } })).data! });
const diagnosticCopy = ref('');
const diagnosticAction = computed(() => {
  switch (diagnostics.data.value?.suggestedAction) {
    case 'CONTACT_OPERATOR_FOR_RECONCILIATION': return '调用结果待对账，请联系运维核对。此任务不会自动重放。';
    case 'CHECK_ACCESS_AND_CREDENTIAL': return '请检查当前账号权限和任务所用凭据。';
    case 'CHECK_CONFIGURATION_AND_BUDGET': return '请检查模型配置、可用余额和聊天额度。';
    case 'CHECK_SERVICE_STATUS': return '服务暂时不可用，请稍后核对状态。';
    case 'CONTACT_OPERATOR': return '请提供诊断编号联系运维核对。';
    default: return '';
  }
});
async function copyDiagnostic() {
  const id = diagnostics.data.value?.traceId; if (!id) return;
  try { await navigator.clipboard.writeText(id); diagnosticCopy.value = '诊断编号已复制'; }
  catch { diagnosticCopy.value = '请选中诊断编号手动复制'; }
}
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
    case 'CREDENTIAL_REVOKED': return '本次任务的凭据已撤销，请检查模型密钥。旧任务不会自动切换密钥。';
    case 'PROVIDER_BALANCE_EXHAUSTED': return '供应商余额或额度不足，请检查远程账户。本次调用不会自动重试。';
    case 'PROVIDER_TRUNCATED': return '模型回复未完整结束，已保留收到的内容。';
    case 'TASK_CANCEL_REQUESTED': return '回复已取消。';
    case null: case undefined: return '';
    default: return '回复未能完成，已保留现有记录。';
  }
});
const labels: Record<string, string> = { WAITING_INPUT: '等待你的回答', QUEUED: '排队中', RUNNING: '正在回复', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消', BLOCKED: '需要处理' };
let stream: (() => void) | undefined;
let generation = 0;
const streamStatus = ref<StreamStatus>('connecting');
let mounted = true;

async function csrf() {
  const value = (await api.GET('/api/v1/auth/csrf')).data!;
  setCsrf(value.csrfToken); return value.csrfToken;
}
function stopStream() { generation++; stream?.(); stream = undefined; }
function connect() {
  if (!mounted || stream || !actor.value || !space.value || !selected.value || !cache.getQueryData<ChatSnapshot>(snapshotKey.value)) return;
  const conversation = selected.value; const key = [...snapshotKey.value];
  const current = ++generation; const user = actor.value; const selectedSpace = space.value;
  const valid = () => mounted && generation === current && conversation === selected.value && user === actor.value && selectedSpace === space.value;
  stream = subscribeReplay({
    conversation, space: selectedSpace,
    cursor: () => cache.getQueryData<ChatSnapshot>(key)?.cursor ?? 0,
    apply: body => {
      if (!valid()) return;
      cache.setQueryData<ChatSnapshot>(key, previous => {
        if (!previous) throw new SnapshotRequired('Canonical projection is missing');
        return applyChatEvent(previous, body);
      });
      if (body.type === 'task.permissions_changed') void cache.invalidateQueries({ queryKey: ['chat', user, selectedSpace, 'permissions'] });
      if (body.type === 'usage.updated') { void cache.invalidateQueries({ queryKey: ['chat', user, 'usage'] }); void quota.refetch(); void config.refetch(); }
    },
    rebase: async signal => {
      const result = await api.GET('/api/v1/conversations/{id}/snapshot', { signal, params: { path: { id: conversation }, header: { 'X-Space-Id': selectedSpace } } });
      if (valid() && !signal.aborted) cache.setQueryData(key, result.data!);
    },
    status: value => { if (valid()) streamStatus.value = value; },
    unauthorized: () => { if (valid()) globalThis.dispatchEvent(new Event('platform:session-expired')); },
    failed: () => { if (valid()) error.value = '实时连接已暂停，已有消息已保留，请刷新核对。'; },
  });
}
watch(() => snapshot.data.value, connect);
let restoring = false;
function restoreDraft() {
  if (!actor.value || !space.value) return;
  restoring = true;
  const value = drafts.load(actor.value, space.value, selected.value);
  attachmentDrafts.value = value.attachments ?? [];
  planMode.value = value.planMode ?? false; workspaceId.value = value.workspaceId ?? ''; permissionMode.value = value.permissionMode ?? 'RISK_BASED'; draft.value = value.text; pending.value = value.pending; selectedModel.value = value.model || effective.data.value?.values.modelId || ''; source.value = value.model ? value.source : effective.data.value?.values.credentialSource || 'PLATFORM';configurationChoices.value = value.configurationChoices ?? { model: '', source: '', plan: '', permission: '' };
  restoring = false;
}
watch([actor, space, selected], () => { stopStream(); sourcePreview.value = undefined; error.value = ''; restoreDraft(); connect(); }, { immediate: true, flush: 'sync' });
watch(() => effective.data.value, () => { if (!personalConfigEnabled && !selectedModel.value) restoreDraft(); });
watch([draft, selectedModel, source, pending, planMode, workspaceId, permissionMode, attachmentDrafts, configurationChoices], () => { if (mounted && !restoring) drafts.save(actor.value, space.value, selected.value, { text: draft.value, model: selectedModel.value, source: source.value, planMode: planMode.value, workspaceId: workspaceId.value, permissionMode: permissionMode.value, attachments: attachmentDrafts.value, configurationChoices: { ...configurationChoices.value }, ...(pending.value ? { pending: pending.value } : {}) }); }, { deep: true, flush: 'sync' });
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
  if (busy.value || (!pending.value && (!draft.value.trim() || running.value || attachmentsProcessing.value))) return;
  if (!pending.value) {
    if (personalConfigEnabled) {
      const preview=effective.data.value;if(!preview?.canSubmit || effective.isFetching.value){error.value='生效配置不可用或正在更新，请检查后再发送。';return;}
      const choices=configurationChoices.value;
      pending.value = { key:newRequestId(),text:draft.value,model:preview.values.modelId,source:preview.values.credentialSource,conversation:selected.value,
        attachments:attachmentRefs.value.map(value=>({...value})), configurationOptions: {
          ...(workspaceId.value?{workspaceId:workspaceId.value}:{}),...(choices.model?{modelId:choices.model}:{}),...(choices.source?{credentialSource:choices.source}:{}),
          ...(choices.plan?{planMode:choices.plan==='ON'}:{}),...(choices.permission?{permissionMode:choices.permission}:{}),expectedEffectiveConfigHash:preview.configHash,
        } };
    } else {
      const model=models.data.value?.items.find(item=>item.id===selectedModel.value);if(!model){error.value='所选模型或凭据不可用，请检查配置。';return;}
      pending.value={ key:newRequestId(),text:draft.value,model:model.id,source:source.value,conversation:selected.value,planMode:planEnabled&&planMode.value,workspaceId:permissionsEnabled?workspaceId.value||null:null,permissionMode:permissionsEnabled?permissionMode.value:'RISK_BASED',attachments:attachmentRefs.value.map(value=>({...value})) };
    }
  }
  const value = pending.value; busy.value = true; error.value = '';
  try {
    const token = await csrf();
    const body = { input: { text: value.text, attachments: value.attachments ?? [] }, options: value.configurationOptions ?? { modelId: value.model, credentialSource: value.source, planMode: value.planMode ?? false, permissionMode: value.permissionMode ?? 'RISK_BASED', ...(value.workspaceId ? { workspaceId: value.workspaceId } : {}) } };
    let conversation = value.conversation;
    if (conversation) await api.POST('/api/v1/conversations/{id}/tasks', { params: { path: { id: conversation }, header: { 'Idempotency-Key': value.key, 'X-CSRF-Token': token } }, body });
    else { const accepted = (await api.POST('/api/v1/conversations', { params: { header: { 'Idempotency-Key': value.key, 'X-CSRF-Token': token } }, body: { title: '新对话', initialTask: body } })).data!; conversation = accepted.id; }
    pending.value = undefined; draft.value = ''; attachmentDrafts.value = []; attachmentRefs.value = []; stopStream(); await cache.invalidateQueries({ queryKey: listKey.value });
    if (!value.conversation) {
      drafts.save(actor.value, space.value, '', { text: '', model: '', source: 'PLATFORM' });
      drafts.save(actor.value, space.value, conversation, { text: '', model: value.model, source: value.source, configurationChoices: { ...configurationChoices.value } });
      await router.push(`/app/chat/${conversation}`);
    }
    await snapshot.refetch(); await config.refetch(); connect();
  } catch (failure) {
    if (failure instanceof ApiError && failure.problem.status === 412 && failure.problem.code === 'EFFECTIVE_CONFIGURATION_CHANGED') {
      pending.value=undefined;await effective.refetch();error.value='生效配置已变化，输入和附件已保留；请核对后重新发送。';
    } else if (failure instanceof ApiError && [400, 403, 404, 422, 429].includes(failure.problem.status)) {
      pending.value = undefined;
      error.value = failure.problem.code === 'CREDENTIAL_SOURCE_UNAVAILABLE'
        ? '所选凭据未配置或已撤销，请检查模型密钥与来源。输入已保留。'
        : ['QUOTA_EXCEEDED', 'DAILY_TOKEN_QUOTA_EXCEEDED'].includes(failure.problem.code) ? '聊天额度不足，输入已保留，请联系运维核对额度。'
        : '提交被拒绝，输入已保留，请核对输入和配置。';
    }
    else error.value = '提交暂未确认。重试会复用原消息和请求标识，不重复创建任务。';
  }
  finally { busy.value = false; }
}
async function cancel() {
  const task = lastTask.value; if (!task || busy.value) return; busy.value = true;
  try {
    const token = await csrf();
    await api.POST('/api/v1/tasks/{id}/cancel', { params: { path: { id: task.taskId }, header: { 'Idempotency-Key': newRequestId(), 'X-CSRF-Token': token } }, body: {} });
    await snapshot.refetch();
  } catch { error.value = '取消暂未确认，请稍后重试。'; }
  finally { busy.value = false; }
}
</script>

<template>
  <main id="main-content" :class="['chat-layout', { 'workbench-layout': attachmentsEnabled }]" :data-view="workbenchView" tabindex="-1">
    <nav v-if="attachmentsEnabled" class="workbench-tabs" aria-label="工作台布局">
      <button type="button" :aria-pressed="workbenchView === 'CHAT'" @click="selectWorkbench('CHAT')">聊天</button>
      <button type="button" :aria-pressed="workbenchView === 'FILES'" @click="selectWorkbench('FILES')">文件</button>
      <button type="button" class="split-view-option" :aria-pressed="workbenchView === 'SPLIT'" @click="selectWorkbench('SPLIT')">分栏</button>
      <RouterLink to="/app/storage">数据与存储</RouterLink>
    </nav>
    <aside class="chat-sidebar" aria-label="对话导航">
      <p class="eyebrow">个人空间</p>
      <button :disabled="busy || !models.data.value?.items.length" @click="create">新建对话</button>
      <nav aria-label="历史对话"><RouterLink v-for="item in conversations.data.value?.items" :key="item.id" :to="`/app/chat/${item.id}`" :aria-current="selected === item.id ? 'page' : undefined">{{ item.title }}</RouterLink></nav>
      <button v-if="conversations.data.value?.nextCursor" :disabled="busy" @click="moreConversations">更多对话</button>
      <RouterLink to="/app/settings/account">账号信息</RouterLink>
    </aside>
    <section class="chat-content" aria-label="普通聊天">
      <header><h1>普通聊天</h1><p class="intro">{{ models.data.value?.items.find(item => item.id === selectedModel)?.name ?? '请选择模型' }}</p></header>
      <p v-if="effective.data.value?.context" class="intro" data-testid="context-capacity">任务上下文 {{ effective.data.value.context.window.toLocaleString() }} tokens，输入可用 {{ effective.data.value.context.inputLimit.toLocaleString() }}，输出上限 {{ effective.data.value.context.maxOutput.toLocaleString() }}；模型能力 {{ effective.data.value.context.modelWindow.toLocaleString() }}。与每日额度分别管理，使用量由服务端估算准入及供应商结算。</p>
      <p v-if="!selected" class="intro">新对话草稿，发送首条消息后保存。</p>
      <p v-if="snapshot.isError.value" role="alert">对话暂时不可用。</p>
      <p v-if="selected && ['reconnecting', 'offline'].includes(streamStatus)" role="status">{{ streamStatus === 'offline' ? '网络已断开，联网后继续读取。' : '正在重新连接，已有消息已保留。' }}</p>
      <div class="chat-messages" aria-label="消息历史" :aria-busy="snapshot.isFetching.value">
        <article v-for="message in snapshot.data.value?.messages" :key="message.id" :class="['chat-message', message.role.toLowerCase()]">
          <p class="eyebrow">{{ message.role === 'USER' ? '你' : '助手' }}<span v-if="message.status === 'PARTIAL'"> · 回复未完成</span></p>
          <template v-for="block in message.blocks" :key="block.blockId">
            <p v-if="block.type === 'TEXT'" class="chat-text">{{ block.text || (message.status === 'STREAMING' ? '等待回复…' : '未产生回复') }}</p>
            <aside v-else-if="block.type === 'FILE_REFERENCE'" class="source-card" aria-label="固定附件来源">
              <strong>{{ block.name }}</strong><p>已固定原字节与正文提取版本</p>
              <RouterLink v-if="block.fileId" :to="`/app/workspaces?file=${block.fileId}&version=${block.versionId}`">打开来源</RouterLink>
              <a v-if="block.attachmentId" :href="`/api/v1/attachments/${block.attachmentId}/content`">下载原文件</a>
              <button type="button" @click="sourcePreview = block">预览固定来源</button>
            </aside>
          </template>
          <p v-if="!message.blocks.length" class="chat-text">暂无可显示的文本内容。</p>
        </article>
      </div>
      <FilePreviewPane v-if="sourcePreview && actor && space" :key="`${actor}:${space}:${sourcePreview.blockId}`" :file-id="sourcePreview.fileId" :version-id="sourcePreview.versionId" :attachment-id="sourcePreview.attachmentId" :name="sourcePreview.name" :user-id="actor" :space-id="space" historical @close="sourcePreview = undefined" />
      <template v-for="interaction in snapshot.data.value?.pending" :key="interaction.interactionId">
        <PlanCard v-if="interaction.kind === 'PLAN_APPROVAL'" :interaction="{ ...interaction, question: null }" :actor="actor" :space="space" :ready="snapshot.data.value?.tasks.find(task => task.taskId === interaction.taskId)?.status === 'WAITING_INPUT'" @refresh="refreshInteractions" />
        <QuestionCard v-else-if="interaction.kind === 'QUESTION'" :interaction="{ ...interaction, plan: null }" :ready="interaction.optional || snapshot.data.value?.tasks.find(task => task.taskId === interaction.taskId)?.status === 'WAITING_INPUT'" @refresh="refreshInteractions" />
        <ApprovalCard v-else-if="interaction.kind === 'TOOL_APPROVAL'" :interaction="{ ...interaction, plan: null, question: null }" :ready="snapshot.data.value?.tasks.find(task => task.taskId === interaction.taskId)?.status === 'WAITING_INPUT'" @refresh="refreshInteractions" />
      </template>
      <ToolOperationsPanel v-if="workspaceToolsEnabled && lastTask" :key="lastTask.taskId" :actor="actor" :space="space" :task-id="lastTask.taskId" :task-version="lastTask.version" :active="['QUEUED', 'RUNNING', 'WAITING_INPUT'].includes(lastTask.status)" />
      <p v-if="lastTask" role="status">{{ labels[lastTask.status] }}{{ lastTask.cancelRequested && running ? ' · 正在取消' : '' }}<span v-if="reason"> · {{ reason }}</span></p>
      <p v-if="stopLabel" role="status" data-testid="stop-stage">{{ stopLabel }}</p>
      <aside v-if="(reconciliationState?.unknownCalls ?? 0) > 0" aria-label="结果待核对" class="intro" data-testid="reconciliation-notice">
        <p>调用结果或费用待运维核对，预留额度仍保留；此任务不会自动重放。</p>
        <p v-if="reconciliationState?.excludedFromContext">该未完成轮次未进入后续模型上下文；页面保留的部分回复不代表成功结果。</p>
        <p v-if="reconciliationState?.stopConfirmed">执行者已停止；可在剩余额度内发送独立新消息。</p>
      </aside>
      <TaskPermissions v-if="permissionsEnabled && lastTask" :key="lastTask.taskId" :actor="actor" :space="space" :task-id="lastTask.taskId" :task-version="lastTask.version" :workspace-tools-enabled="workspaceToolsEnabled" />
      <RecoveryNotice v-if="lastTask" :actor="actor" :space="space" :task-id="lastTask.taskId" :version="lastTask.version" :interrupted="lastTask.status === 'BLOCKED' || lastTask.status === 'FAILED'" />
      <section v-if="lastTask?.reasonCode && diagnostics.data.value" aria-label="任务诊断" class="task-diagnostics">
        <p>{{ diagnosticAction }}</p><p v-if="diagnostics.data.value.hasPartialOutput">已保留部分回复。</p>
        <p class="chat-text">诊断编号：{{ diagnostics.data.value.traceId }}</p>
        <button type="button" @click="copyDiagnostic">复制诊断编号</button><span role="status">{{ diagnosticCopy }}</span>
      </section>
      <p v-if="lastTask?.reasonCode && diagnostics.isError.value" role="alert">暂时无法读取诊断记录，已有消息已保留。</p>
      <p v-if="usedTokens !== undefined" class="intro">{{ usedTokens === null ? '用量待对账，未将未知用量记为零。' : `本次使用 ${usedTokens} tokens` }}</p>
      <p v-if="error" role="alert" class="identity-error">{{ error }}</p>
      <p v-if="tokenQuota" role="status" data-testid="daily-quota">{{ tokenQuota.unlimited ? '管理员额度不限，用量持续记录' : `今日剩余 ${tokenQuota.remaining?.toLocaleString()} tokens / ${tokenQuota.limit?.toLocaleString()}` }}<span v-if="!tokenQuota.unlimited"> · {{ new Date(tokenQuota.resetAt).toLocaleString() }} 重置</span></p>
      <form class="chat-composer" @submit.prevent="submit">
        <div v-if="personalConfigEnabled" class="chat-options">
          <label for="chat-model">本次模型</label><select id="chat-model" v-model="configurationChoices.model" :disabled="busy || !!pending"><option value="">继承默认</option><option v-if="configurationChoices.model && !models.data.value?.items.some(item => item.id === configurationChoices.model)" :value="configurationChoices.model" disabled>已选模型不可用</option><option v-for="item in models.data.value?.items" :key="item.id" :value="item.id">{{ item.name }}</option></select>
          <label for="chat-source">本次凭据来源</label><select id="chat-source" v-model="configurationChoices.source" :disabled="busy || !!pending"><option value="">继承默认</option><option value="PLATFORM">平台密钥</option><option value="USER">个人密钥</option></select>
          <RouterLink to="/app/settings/general">管理默认设置</RouterLink>
        </div>
        <div v-else class="chat-options">
          <label for="chat-model">模型</label><select id="chat-model" v-model="selectedModel" :disabled="busy || !!pending"><option v-for="item in models.data.value?.items" :key="item.id" :value="item.id">{{ item.name }}</option></select>
          <label for="chat-source">凭据来源</label><select id="chat-source" v-model="source" :disabled="busy || !!pending"><option value="PLATFORM">平台密钥</option><option value="USER">个人密钥</option></select>
          <button type="button" :disabled="busy || !!pending || !selectedModel" @click="saveDefaults">设为我的默认</button>
        </div>
        <p v-if="personalConfigEnabled && effective.data.value" class="intro">本次生效模型：{{ models.data.value?.items.find(item => item.id === effective.data.value?.values.modelId)?.name ?? effective.data.value.values.modelId }}（{{ effective.data.value.sources.modelId }}）；凭据：{{ effective.data.value.values.credentialSource }}（{{ effective.data.value.sources.credentialSource }}）。</p>
        <p v-else-if="!personalConfigEnabled" class="intro">选择仅影响下次发送。{{ effective.data.value?.sources.modelId === 'USER' ? '默认模型来自个人偏好。' : '默认模型来自平台。' }}</p>
        <p v-if="personalConfigEnabled && effective.isError.value" role="alert">无法读取生效配置，请检查设置与权限。</p>
        <p v-if="personalConfigEnabled && effective.data.value?.canSubmit === false" role="alert">当前配置不可提交：{{ effective.data.value.blockers?.join('、') }}</p>
        <div v-if="permissionsEnabled" class="chat-options">
          <label for="chat-workspace">本次工作区</label><select id="chat-workspace" v-model="workspaceId" :disabled="busy || !!pending"><option value="">不绑定（普通文本）</option><option v-for="item in workspaces.data.value?.items.filter(w => w.state === 'READY')" :key="item.id" :value="item.id">{{ item.name }}</option></select>
          <label for="chat-permission">本次权限</label><select v-if="personalConfigEnabled" id="chat-permission" v-model="configurationChoices.permission" :disabled="busy || !!pending"><option value="">继承默认</option><option value="RISK_BASED">按风险确认</option><option value="FULL">任务内完全权限</option></select><select v-else id="chat-permission" v-model="permissionMode" :disabled="busy || !!pending"><option value="RISK_BASED">按风险确认</option><option value="FULL">任务内完全权限</option></select>
          <p v-if="workspaceToolsEnabled && workspaceId">{{ (personalConfigEnabled ? effective.data.value?.values.permissionMode : permissionMode) === 'FULL' ? '完全权限可自动执行工作区命令，命令能创建、修改和删除整个绑定工作区内的文件；仍受实时权限和环境隔离限制。' : '每个执行命令均需单独批准。文件授权不会自动授予命令执行许可。' }}</p>
          <p v-if="workspaces.isError.value" role="alert">暂时无法读取工作区，可继续不绑定的文本聊天。</p>
        </div>
        <label v-if="planEnabled && personalConfigEnabled" for="chat-plan">本次计划模式</label><select v-if="planEnabled && personalConfigEnabled" id="chat-plan" v-model="configurationChoices.plan" :disabled="busy || !!pending || running"><option value="">继承默认</option><option value="ON">开启</option><option value="OFF">关闭</option></select>
        <label v-else-if="planEnabled" class="plan-toggle"><input v-model="planMode" type="checkbox" :disabled="busy || !!pending || running" /> {{ workspaceToolsEnabled ? '先制定计划，再确认执行' : '先制定计划，再确认文本执行' }}</label>
        <label for="chat-input">消息</label>
        <p v-if="attachmentsEnabled && linkedSource" role="status">已选择固定产物版本，尚未添加到消息。<button type="button" :disabled="busy || !!pending" @click="addLinkedSource">添加此来源</button></p>
        <AttachmentComposer v-if="attachmentsEnabled" ref="attachmentComposer" :key="`${actor}:${space}:${selected}`" v-model="attachmentDrafts" :actor="actor" :space="space" :disabled="busy || !!pending" @references="attachmentRefs = $event" @processing="attachmentsProcessing = $event" />
        <p v-if="drafts.storageWarning" role="alert">浏览器草稿空间不足，内容仅当前页面保留；请先保存或发送。</p>
        <textarea id="chat-input" v-model="draft" rows="3" :disabled="busy || !!pending" placeholder="输入文字，按 Ctrl + Enter 发送" @keydown.ctrl.enter.prevent="submit" @keydown.meta.enter.prevent="submit" />
        <div class="chat-actions"><button type="submit" :disabled="busy || (!pending && (running || !draft.trim() || attachmentsProcessing || !modelAvailable))">{{ pending ? '重试同一条消息' : '发送' }}</button><button v-if="running" type="button" :disabled="busy || lastTask?.cancelRequested" @click="cancel">取消回复</button></div>
      </form>
      <details v-if="config.data.value"><summary>当前任务配置</summary><p>模型：{{ config.data.value.values.modelName }}；来源：{{ config.data.value.values.credentialSource }}</p><p v-if="snapshotResolution?.resolverVersion === 'LEGACY'">历史任务：没有配置来源记录。</p><p v-else-if="snapshotResolution?.sources">冻结来源：模型 {{ snapshotResolution.sources.modelId }}、凭据 {{ snapshotResolution.sources.credentialSource }}、计划 {{ snapshotResolution.sources.planMode }}、权限 {{ snapshotResolution.sources.permissionMode }}。</p><p>冻结上下文 {{ config.data.value.values.window }} tokens，输出上限 {{ config.data.value.values.maxOutput }}。</p><p v-if="config.data.value.contextUsage">最近请求输入估算：{{ config.data.value.contextUsage.estimatedInputTokens?.toLocaleString() ?? '调用前计算' }} tokens；估算不等于计费值。</p><p class="chat-text">快照 {{ config.data.value.configSnapshotId }} · {{ config.data.value.configHash }}</p><p v-if="!config.data.value.restrictions.credentialActive">此任务凭据已撤销。</p></details>
      <details v-if="knowledgeEnabled && contextSummary.data.value"><summary>任务上下文摘要</summary><p>有效输入上限 {{ contextSummary.data.value.inputLimit.toLocaleString() }} tokens；当前估算 {{ contextSummary.data.value.estimatedInputTokens?.toLocaleString() ?? '尚无估算' }}。估算值不等于计费值。</p><p v-if="contextSummary.data.value.projection">已提交压缩投影 {{ contextSummary.data.value.projection.epoch }} · 来源 {{ contextSummary.data.value.projection.sourceManifestSha256 }} · 摘要 {{ contextSummary.data.value.projection.summarySha256 }}</p><p v-else>当前没有压缩投影；任务原始历史保持不变。</p></details>
      <ModelSettings v-if="actor" :actor="actor" />
    </section>
    <aside v-if="attachmentsEnabled" class="workbench-files" aria-label="文件工作台">
      <label for="workbench-workspace">浏览工作区</label>
      <select id="workbench-workspace" v-model="workbenchWorkspace"><option value="">{{ workspaceId ? '使用当前草稿绑定的工作区' : '请选择工作区' }}</option><option v-for="item in workspaces.data.value?.items.filter(item => item.state === 'READY')" :key="item.id" :value="item.id">{{ item.name }}</option></select>
      <p>浏览或引用文件不自动授予修改权限；“让AI修改”只准备草稿，不发送任务。</p>
      <WorkspaceFilesPanel v-if="workbenchFile && actor && space" :key="`${actor}:${space}:${workbenchFile.id}`" :workspace-id="workbenchFile.id" :user-id="actor" :space-id="space" storage-ready reference-enabled @reference="referenceFile($event)" @modify="referenceFile($event, true)" />
      <p v-else role="status">选择已就绪的工作区以浏览、编辑、预览或引用固定版本。普通聊天上传不需要工作区。</p>
      <details v-if="preferences.data.value?.recentFiles?.length"><summary>最近引用的文件</summary><ul><li v-for="file in preferences.data.value.recentFiles" :key="`${file.fileId}:${file.versionId}`"><RouterLink :to="`/app/workspaces/${file.workspaceId}?file=${file.fileId}&version=${file.versionId}`">打开固定版本 {{ file.versionId.slice(0, 8) }}</RouterLink></li></ul></details>
    </aside>
  </main>
</template>
