<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { api } from '../../shared/api/client';
import { newRequestId } from '../../shared/api/request-id';
import { setCsrf } from '../../shared/api/session';
import { ApiError } from '../../shared/api/request';
import { fileHash } from '../workspace/file-transfers';
import { continueSkillImport, skillImportStatus, type PendingSkillImport } from './skill-import-coordinator';
import { clearSkillImports, loadSkillImport, saveSkillImport, skillImportScope } from './skill-import-requests';
import type { components } from '../../shared/api/generated/http';
import ConnectorPanel from './ConnectorPanel.vue';
import BindingPanel from './BindingPanel.vue';

type Skill = components['schemas']['SkillCatalogItem'];
type Import = components['schemas']['SkillImport'];
const cache = useQueryClient();
const me = useQuery({ queryKey: ['identity', 'me'], queryFn: async () => (await api.GET('/api/v1/me')).data! });
const user = computed(() => me.data.value?.id ?? '');
const space = computed(() => me.data.value?.spaces.find(item => item.type === 'PERSONAL')?.id ?? '');
const scope = computed(() => user.value && space.value ? skillImportScope(user.value, space.value) : '');
const catalogCursor = ref<string>(); const versionCursor = ref<string>();
const catalog = useQuery({ queryKey: computed(() => ['capabilities', user.value, space.value, 'skills', catalogCursor.value]),
  enabled: computed(() => !!space.value), queryFn: async () => (await api.GET('/api/v1/skills',
    { params: { header: { 'X-Space-Id': space.value }, query: { limit: 100, ...(catalogCursor.value ? { cursor: catalogCursor.value } : {}) } } })).data! });
const selected = ref<Skill>();
const versions = useQuery({ queryKey: computed(() => ['capabilities', user.value, space.value, 'versions', selected.value?.capabilityId, versionCursor.value]),
  enabled: computed(() => !!space.value && !!selected.value), queryFn: async () => (await api.GET('/api/v1/skills/{id}/versions',
    { params: { path: { id: selected.value!.capabilityId }, header: { 'X-Space-Id': space.value }, query: { limit: 100, ...(versionCursor.value ? { cursor: versionCursor.value } : {}) } } })).data! });
const detail = ref<components['schemas']['SkillVersion']>();
const candidate = ref<components['schemas']['CapabilitySelection']>();
const pending = ref<PendingSkillImport>(); const state = ref<Import>(); const file = ref<File>();
const busy = ref(false); const message = ref(''); const progress = ref(0); const requestNotFound = ref(false);
let controller = new AbortController();
watch(scope, value => { pending.value = value ? loadSkillImport(value) : undefined; state.value = undefined; file.value = undefined; requestNotFound.value = false; }, { immediate: true });
watch(() => selected.value?.capabilityId, () => { versionCursor.value = undefined; });
function persist() { if (!scope.value || !saveSkillImport(scope.value, pending.value)) throw new Error('无法保存导入身份，请检查浏览器存储'); }
function expired() { controller.abort(); clearSkillImports(); pending.value = undefined; file.value = undefined; detail.value = undefined; candidate.value = undefined; }
onMounted(() => globalThis.addEventListener('platform:session-expired', expired));
onBeforeUnmount(() => { controller.abort(); globalThis.removeEventListener('platform:session-expired', expired); });
function picked(event: Event) { file.value = (event.target as HTMLInputElement).files?.[0]; }
async function begin() {
  if (busy.value || !file.value || !space.value || pending.value) return;
  busy.value = true; message.value = ''; controller = new AbortController();
  try {
    if (file.value.size > 20 * 1024 * 1024) throw new Error('压缩包不能超过 20 MiB');
    const hash = await fileHash(file.value, controller.signal);
    pending.value = { requestId: newRequestId(), completionId: newRequestId(), body: { sizeBytes: file.value.size, sha256: hash,
      ...(selected.value ? { capabilityId: selected.value.capabilityId } : {}) } };
    persist();
    state.value = await continueSkillImport(pending.value, file.value, space.value, controller.signal, persist, n => { progress.value = n; });
    message.value = '导入请求已提交，请查看处理状态。';
  } catch (error) { message.value = error instanceof Error ? error.message : '导入尚未确认，请查询原请求。'; }
  finally { busy.value = false; }
}
async function check() {
  if (busy.value || !pending.value || !space.value) return;
  busy.value = true; message.value = ''; controller = new AbortController();
  try {
    state.value = await skillImportStatus(pending.value, space.value, controller.signal, persist); requestNotFound.value = false;
    if (state.value.state === 'DRAFT') await cache.invalidateQueries({ queryKey: ['capabilities', user.value, space.value] });
  } catch (error) {
    requestNotFound.value = !pending.value?.importId && error instanceof ApiError && error.problem.status === 404;
    message.value = requestNotFound.value ? '尚未找到原请求，可以使用同一请求身份继续。' : '状态暂未确认；保留原请求，稍后可再次查询。';
  }
  finally { busy.value = false; }
}
async function resume() {
  if (busy.value || !pending.value || !space.value) return;
  busy.value = true; message.value = ''; controller = new AbortController();
  try { persist(); state.value = await continueSkillImport(pending.value, file.value, space.value, controller.signal, persist, n => { progress.value = n; }); requestNotFound.value = false; }
  catch (error) { message.value = error instanceof Error ? error.message : '操作未确认，请查询原请求。'; }
  finally { busy.value = false; }
}
function dismiss() {
  if (!scope.value || !state.value || !['DRAFT', 'REJECTED'].includes(state.value.state)) return;
  saveSkillImport(scope.value); pending.value = undefined; state.value = undefined; file.value = undefined;
}
async function show(item: Skill) {
  message.value = ''; detail.value = undefined;
  try { detail.value = (await api.GET('/api/v1/skills/{id}/versions/{versionId}', { params: { path: { id: item.capabilityId,
    versionId: item.versionId }, header: { 'X-Space-Id': space.value } } })).data; }
  catch { message.value = '无法读取该版本，请检查权限或连接。'; }
}
async function change(action: 'publish' | 'revoke') {
  if (!detail.value || busy.value) return;
  const chosen = detail.value; busy.value = true; message.value = '';
  try {
    const token = (await api.GET('/api/v1/auth/csrf')).data?.csrfToken; if (!token) throw new Error('安全验证暂不可用'); setCsrf(token);
    const options = { params: { path: { id: chosen.capabilityId, versionId: chosen.versionId }, header: { 'X-Space-Id': space.value,
      'X-CSRF-Token': token, 'If-Match': `"${chosen.revision}"`, 'Idempotency-Key': newRequestId() } } };
    const result = action === 'publish' ? await api.POST('/api/v1/skills/{id}/versions/{versionId}/publish', options)
      : await api.POST('/api/v1/skills/{id}/versions/{versionId}/revoke', options);
    detail.value = result.data; await cache.invalidateQueries({ queryKey: ['capabilities', user.value, space.value] });
  } catch { message.value = '结果未确认或版本已变化，请重新读取版本后核对。'; detail.value = undefined; }
  finally { busy.value = false; }
}
</script>

<template>
  <main id="main-content" class="capability-page" tabindex="-1">
    <h1>能力管理</h1>
    <p>管理固定版本的 Skill。绑定后的聊天自动使用将在后续阶段开放。</p>
    <p v-if="message" role="status">{{ message }}</p>
    <section aria-labelledby="import-heading">
      <h2 id="import-heading">导入 Skill</h2>
      <p>选择 ZIP 包，最大 20 MiB。{{ selected ? '将为已选 Skill 创建新版本。' : '将创建新的 Skill。' }}</p>
      <label for="skill-file">Skill 压缩包</label> <input id="skill-file" type="file" accept=".zip,application/zip" :disabled="busy" @change="picked">
      <button type="button" :disabled="busy || !file || !!pending" @click="begin">开始导入</button>
      <template v-if="pending">
        <p role="status">状态：{{ state?.state ?? '等待查询' }} <span v-if="progress">{{ progress }}%</span></p>
        <button type="button" :disabled="busy" @click="check">查询原请求</button>
        <button v-if="requestNotFound" type="button" :disabled="busy" @click="resume">继续原请求</button>
        <button v-if="state && ['CREATED', 'VERIFIED'].includes(state.state)" type="button" :disabled="busy" @click="resume">继续原请求</button>
        <button v-if="state && ['DRAFT', 'REJECTED'].includes(state.state)" type="button" :disabled="busy" @click="dismiss">完成并关闭</button>
        <p v-if="state?.state === 'BLOCKED' || state?.state === 'UPLOADING'">结果待核对，不会自动重复上传。</p>
      </template>
    </section>
    <section aria-labelledby="skills-heading">
      <h2 id="skills-heading">Skill 列表</h2>
      <p v-if="catalog.isPending.value">正在读取…</p><p v-else-if="catalog.isError.value" role="alert">无法读取 Skill 列表。</p>
      <p v-else-if="!catalog.data.value?.items.length">还没有 Skill。</p>
      <ul><li v-for="item in catalog.data.value?.items" :key="item.capabilityId">
        <button type="button" @click="selected = item; detail = undefined">{{ item.name }}</button> — {{ item.description }}
      </li></ul>
      <button v-if="catalogCursor" type="button" @click="catalogCursor = undefined">返回首页</button>
      <button v-if="catalog.data.value?.nextCursor" type="button" @click="catalogCursor = catalog.data.value?.nextCursor ?? undefined">下一页</button>
    </section>
    <section v-if="selected" aria-label="Skill 版本">
      <h2>{{ selected.name }} 的版本</h2><button type="button" @click="selected = undefined; detail = undefined">取消选择</button>
      <p v-if="versions.isPending.value">正在读取版本…</p><p v-else-if="versions.isError.value" role="alert">无法读取版本。</p>
      <ul><li v-for="item in versions.data.value?.items" :key="item.versionId">
        <button type="button" @click="show(item)">版本 {{ item.versionNumber }} — {{ item.state }}</button>
      </li></ul>
      <button v-if="versionCursor" type="button" @click="versionCursor = undefined">返回首页</button>
      <button v-if="versions.data.value?.nextCursor" type="button" @click="versionCursor = versions.data.value?.nextCursor ?? undefined">下一页</button>
    </section>
    <section v-if="detail" aria-label="版本详情">
      <h2>{{ detail.name }} — {{ detail.state }}</h2><p>{{ detail.description }}</p>
      <p>资源 {{ detail.resources.length }} 项；依赖 {{ detail.dependencies.length }} 项。</p>
      <details><summary>查看资源</summary><ul><li v-for="resource in detail.resources" :key="resource.path">{{ resource.path }} — {{ resource.bytes }} 字节</li></ul></details>
      <button v-if="detail.state === 'DRAFT'" type="button" :disabled="busy" @click="change('publish')">发布版本</button>
      <button v-if="detail.state === 'PUBLISHED'" type="button" :disabled="busy" @click="change('revoke')">撤销版本</button>
      <button v-if="detail.state === 'PUBLISHED' && detail.assetAvailable" type="button"
        @click="candidate = { capabilityId: detail.capabilityId, versionId: detail.versionId }">选择此版本用于绑定</button>
    </section>
    <ConnectorPanel v-if="user && space" :user-id="user" :space-id="space" @candidate="candidate = $event" />
    <BindingPanel v-if="user && space" :user-id="user" :space-id="space" :candidate="candidate" />
  </main>
</template>

<style scoped>
.capability-page { max-width: 70rem; margin: auto; padding: 1rem; }
section { margin: 1rem 0; padding: 1rem; border: 1px solid var(--border-color, #888); border-radius: .5rem; }
button, input { margin: .3rem; } li { margin: .4rem 0; overflow-wrap: anywhere; }
</style>
