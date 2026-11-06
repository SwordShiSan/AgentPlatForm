<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { api } from '../../shared/api/client';
import { setCsrf } from '../../shared/api/session';
import { ApiError } from '../../shared/api/request';
import type { components } from '../../shared/api/generated/http';
import ModelSettings from './ModelSettings.vue';

const route = useRoute();
const cache = useQueryClient();
const workspaceId = computed(() => typeof route.params.id === 'string' ? route.params.id : '');
const isModels = computed(() => route.path.endsWith('/models'));
const me = useQuery({ queryKey: ['identity', 'me'], queryFn: async () => (await api.GET('/api/v1/me')).data! });
const actor = computed(() => me.data.value?.id ?? '');
const key = computed(() => ['settings', actor.value, workspaceId.value || 'personal']);
const settings = useQuery({ queryKey: key, enabled: computed(() => !!actor.value), queryFn: async () => {
  if (workspaceId.value) return (await api.GET('/api/v1/workspaces/{id}/settings', { params: { path: { id: workspaceId.value } } })).data!;
  return (await api.GET('/api/v1/me/preferences')).data!;
} });
const models = useQuery({ queryKey: computed(() => ['chat', actor.value, 'models']), enabled: computed(() => !!actor.value), queryFn: async () => (await api.GET('/api/v1/models')).data! });
const effective = useQuery({ queryKey: computed(() => ['chat', actor.value, 'effective-config', workspaceId.value]), enabled: computed(() => !!actor.value), queryFn: async () => (await api.GET('/api/v1/effective-config', { params: { query: workspaceId.value ? { workspaceId: workspaceId.value } : {} } })).data! });
const quota = useQuery({ queryKey: computed(() => ['chat', actor.value, 'quota']), enabled: computed(() => !!actor.value), queryFn: async () => (await api.GET('/api/v1/me/usage-quota')).data! });
const model = ref(''); const credential = ref(''); const plan = ref(''); const permission = ref('');
const baseline = ref({ model: '', credential: '', plan: '', permission: '' });
const version = ref<number>(); const dirty = ref(false); const busy = ref(false); const notice = ref('');
function loadForm() {
  const data = settings.data.value; if (!data) return;
  const overrides = data.overrides ?? {};
  model.value = overrides.defaultModelId ?? '';
  credential.value = overrides.defaultCredentialSource ?? '';
  plan.value = overrides.defaultPlanEnabled == null ? '' : String(overrides.defaultPlanEnabled);
  permission.value = overrides.defaultApprovalMode ?? '';
  baseline.value = { model: model.value, credential: credential.value, plan: plan.value, permission: permission.value };
  version.value = data.version; dirty.value = false;
}
watch(() => settings.data.value, () => { if (!dirty.value) loadForm(); });
watch(workspaceId, () => { dirty.value = false; version.value = undefined; notice.value = ''; });
async function save() {
  if (busy.value || version.value === undefined) return;
  const target = workspaceId.value; const expected = version.value; const requestKey = [...key.value];
  const body: components['schemas']['UpdateChatPreferences'] = {};
  if (model.value !== baseline.value.model) body.defaultModelId = model.value || null;
  if (credential.value !== baseline.value.credential) body.defaultCredentialSource = (credential.value || null) as 'PLATFORM' | 'USER' | null;
  if (plan.value !== baseline.value.plan) body.defaultPlanEnabled = plan.value === '' ? null : plan.value === 'true';
  if (permission.value !== baseline.value.permission) body.defaultApprovalMode = (permission.value || null) as 'RISK_BASED' | 'FULL' | null;
  if (!Object.keys(body).length) { dirty.value = false; notice.value = '设置没有变化。'; return; }
  busy.value = true; notice.value = '';
  try {
    const csrf = (await api.GET('/api/v1/auth/csrf')).data!; setCsrf(csrf.csrfToken);
    const header = { 'If-Match': `"${expected}"`, 'X-CSRF-Token': csrf.csrfToken };
    if (target) await api.PATCH('/api/v1/workspaces/{id}/settings', { params: { path: { id: target }, header }, body });
    else await api.PATCH('/api/v1/me/preferences', { params: { header }, body });
    if (workspaceId.value === target) dirty.value = false;
    await cache.invalidateQueries({ queryKey: requestKey });
    await cache.invalidateQueries({ queryKey: ['chat', actor.value, 'effective-config'] });
    if (workspaceId.value === target) notice.value = '设置已保存，仅影响之后提交的新任务。';
  } catch (error) {
    if (workspaceId.value === target) notice.value = error instanceof ApiError && error.problem.status === 412
      ? '设置已被其他页面修改，当前输入已保留。请复制或核对后重新加载。'
      : '设置未确认，请检查连接和允许的配置；当前输入已保留。';
  } finally { busy.value = false; }
}
async function reload() { dirty.value = false; await settings.refetch(); loadForm(); notice.value = ''; }
</script>

<template>
  <main id="main-content" class="configuration-page" tabindex="-1">
    <nav aria-label="设置导航"><RouterLink to="/app/settings/account">账号</RouterLink> · <RouterLink to="/app/settings/general">个人默认</RouterLink> · <RouterLink to="/app/settings/models">模型密钥</RouterLink> · <RouterLink to="/app/chat">聊天</RouterLink></nav>
    <h1>{{ isModels ? '模型密钥' : workspaceId ? '工作区覆盖' : '个人默认设置' }}</h1>
    <template v-if="isModels"><ModelSettings v-if="actor" :actor="actor" /><p v-else role="status">正在读取账号…</p></template>
    <template v-else>
      <p>选择“继承”时使用{{ workspaceId ? '个人默认' : '平台默认' }}；设置变化不会改写已有任务。</p>
      <p v-if="settings.isPending.value" role="status">正在读取设置…</p>
      <p v-else-if="settings.isError.value" role="alert">无法读取设置，请确认空间权限并重试。</p>
      <form v-else @submit.prevent="save" @change="dirty = true">
        <label for="cfg-model">默认模型</label>
        <select id="cfg-model" v-model="model" :disabled="busy"><option value="">继承</option><option v-if="model && !models.data.value?.items.some(item => item.id === model)" :value="model" disabled>已选模型不可用</option><option v-for="item in models.data.value?.items" :key="item.id" :value="item.id">{{ item.name }}</option></select>
        <label for="cfg-credential">密钥来源</label>
        <select id="cfg-credential" v-model="credential" :disabled="busy"><option value="">继承</option><option value="PLATFORM">平台密钥</option><option value="USER">个人密钥</option></select>
        <label for="cfg-plan">默认计划模式</label>
        <select id="cfg-plan" v-model="plan" :disabled="busy"><option value="">继承</option><option value="true">开启</option><option value="false">关闭</option></select>
        <label for="cfg-permission">默认权限模式</label>
        <select id="cfg-permission" v-model="permission" :disabled="busy"><option value="">继承</option><option value="RISK_BASED">按风险确认</option><option value="FULL">任务内完全权限</option></select>
        <p>完全权限仍受实时资源权限和平台强制限制；个人密钥不可用时不会自动切换平台密钥。</p>
        <button type="submit" :disabled="busy || !dirty">保存</button> <button type="button" :disabled="busy" @click="reload">重新加载</button>
      </form>
      <p v-if="notice" role="status">{{ notice }}</p>
      <section v-if="effective.data.value" aria-label="已保存的生效配置">
        <h2>已保存的生效配置</h2>
        <p>模型：{{ models.data.value?.items.find(item => item.id === effective.data.value?.values.modelId)?.name ?? effective.data.value.values.modelId }}（{{ effective.data.value.sources.modelId }}）</p>
        <p>密钥来源：{{ effective.data.value.values.credentialSource }}（{{ effective.data.value.sources.credentialSource }}）</p>
        <p>计划模式：{{ effective.data.value.values.planMode ? '开启' : '关闭' }}（{{ effective.data.value.sources.planMode }}）；权限模式：{{ effective.data.value.values.permissionMode }}（{{ effective.data.value.sources.permissionMode }}）</p>
        <p v-if="effective.data.value.lockedFields.length">平台锁定：{{ effective.data.value.lockedFields.join('、') }}</p>
        <p v-if="effective.data.value.canSubmit === false" role="alert">当前配置暂不可提交：{{ effective.data.value.blockers?.join('、') }}</p>
      </section>
      <p v-else-if="effective.isError.value" role="alert">暂时无法读取生效配置。</p>
      <section aria-label="资源限制"><h2>当前资源限制</h2><p v-if="effective.data.value?.context">任务上下文：{{ effective.data.value.context.window }} tokens，最大输出：{{ effective.data.value.context.maxOutput }} tokens。</p><p v-for="item in quota.data.value" :key="item.metric">{{ item.metric }}：{{ item.limit ?? '不限额' }}</p></section>
    </template>
  </main>
</template>

<style scoped>
.configuration-page { max-width: 52rem; margin: 2rem auto; padding: 0 1rem; }
form { display: grid; gap: .6rem; max-width: 36rem; }
select { min-height: 2.5rem; }
nav a { margin-right: .3rem; }
</style>
