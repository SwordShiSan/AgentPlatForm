<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { api } from '../../shared/api/client';
import { ApiError } from '../../shared/api/request';
import { newRequestId } from '../../shared/api/request-id';
import { setCsrf } from '../../shared/api/session';
import { clearConnectorRequests, connectorScope, loadConnectorRequests, saveConnectorRequests, type ConnectorRequests } from './connector-requests';
import type { components } from '../../shared/api/generated/http';

const props = defineProps<{ userId: string; spaceId: string }>();
const emit = defineEmits<{ candidate: [value: components['schemas']['CapabilitySelection']] }>();
const scope = computed(() => connectorScope(props.userId, props.spaceId));
const cache = useQueryClient(); const pending = ref<ConnectorRequests>({});
const credentialCursor = ref<string>(); const connectorCursor = ref<string>(); const versionCursor = ref<number>();
const credentials = useQuery({ queryKey: computed(() => ['capabilities', props.userId, props.spaceId, 'connector-credentials', credentialCursor.value]),
  queryFn: async () => (await api.GET('/api/v1/me/connector-credentials', { params: { header: { 'X-Space-Id': props.spaceId }, query: { limit: 100, ...(credentialCursor.value ? { cursor: credentialCursor.value } : {}) } } })).data! });
const connectors = useQuery({ queryKey: computed(() => ['capabilities', props.userId, props.spaceId, 'connectors', connectorCursor.value]),
  queryFn: async () => (await api.GET('/api/v1/connectors', { params: { header: { 'X-Space-Id': props.spaceId }, query: { limit: 100, ...(connectorCursor.value ? { cursor: connectorCursor.value } : {}) } } })).data! });
type Connector = components['schemas']['McpConnector'];
type Credential = components['schemas']['ConnectorCredential'];
const selected = ref<Connector>();
const editTarget = ref<Connector>();
const versions = useQuery({ queryKey: computed(() => ['capabilities', props.userId, props.spaceId, 'connector-versions', selected.value?.id, versionCursor.value]),
  enabled: computed(() => !!selected.value), queryFn: async () => (await api.GET('/api/v1/connectors/{id}/versions',
    { params: { path: { id: selected.value!.id }, header: { 'X-Space-Id': props.spaceId }, query: { limit: 100, ...(versionCursor.value ? { cursor: versionCursor.value } : {}) } } })).data! });
const detail = ref<components['schemas']['McpVersionDetail']>();
const probe = ref<components['schemas']['McpProbe']>();
const secret = ref(''); const endpoint = ref(''); const auth = ref<'NONE' | 'BEARER' | 'API_KEY'>('NONE');
const credentialId = ref(''); const apiKeyHeader = ref<'X-API-Key' | 'api-key'>('X-API-Key');
const notice = ref(''); const busy = ref(false);
let generation = 0;
watch(scope, value => { generation++; busy.value = false; pending.value = loadConnectorRequests(value); secret.value = ''; selected.value = undefined; editTarget.value = undefined; detail.value = undefined; probe.value = undefined; credentialCursor.value = undefined; connectorCursor.value = undefined; versionCursor.value = undefined; }, { immediate: true });
watch(() => selected.value?.id, () => { versionCursor.value = undefined; });
function persist() { if (!saveConnectorRequests(scope.value, pending.value)) throw new Error('无法保存请求身份，请检查浏览器存储'); }
function reset() { generation++; busy.value = false; secret.value = ''; endpoint.value = ''; credentialId.value = ''; pending.value = {}; editTarget.value = undefined; detail.value = undefined; probe.value = undefined; clearConnectorRequests(); }
onMounted(() => globalThis.addEventListener('platform:session-expired', reset));
onBeforeUnmount(() => { generation++; secret.value = ''; globalThis.removeEventListener('platform:session-expired', reset); });
async function csrf(current: number) { const value = (await api.GET('/api/v1/auth/csrf')).data?.csrfToken;
  if (current !== generation || !value) throw new Error('安全验证暂不可用'); setCsrf(value); return value; }
async function refresh() {
  // A failed refresh cannot undo an already confirmed write.
  await cache.invalidateQueries({ queryKey: ['capabilities', props.userId, props.spaceId] }).catch(() => undefined);
}
function rejected(error: unknown) { return error instanceof ApiError && [400, 412, 422, 428].includes(error.problem.status); }
async function createCredential() {
  if (busy.value || !secret.value || pending.value.credentialRequest) return;
  busy.value = true; notice.value = ''; let sent = false; const current = generation;
  try {
    const token = await csrf(current); if (current !== generation) return; pending.value.credentialRequest = newRequestId(); persist();
    const value = secret.value; secret.value = ''; sent = true;
    const result = await api.POST('/api/v1/me/connector-credentials', { body: { secret: value }, params: { header: {
      'X-Space-Id': props.spaceId, 'X-CSRF-Token': token, 'Idempotency-Key': pending.value.credentialRequest } } });
    if (current !== generation) return; if (!result.data) throw new Error('密钥创建尚未确认');
    credentialId.value = result.data.id; delete pending.value.credentialRequest; persist();
    await refresh(); notice.value = '密钥已保存，内容不会再次显示。';
  } catch (error) { if (current !== generation) return; secret.value = '';
    if (!sent || rejected(error)) { delete pending.value.credentialRequest; saveConnectorRequests(scope.value, pending.value); }
    notice.value = !sent ? '操作未发送，请检查连接与浏览器存储。' : rejected(error) ? '请求未被接受，请检查输入。' : '结果尚未确认，请查询原请求；不会自动再次发送密钥。'; }
  finally { if (current === generation) busy.value = false; }
}
async function recoverCredential() {
  if (busy.value || !pending.value.credentialRequest) return; busy.value = true; notice.value = ''; const current = generation;
  try {
    const result = await api.GET('/api/v1/me/connector-credentials/requests/{requestId}', { params: {
      path: { requestId: pending.value.credentialRequest }, header: { 'X-Space-Id': props.spaceId } } });
    if (current !== generation) return; if (!result.data) throw new Error(); credentialId.value = result.data.state === 'ACTIVE' ? result.data.id : ''; delete pending.value.credentialRequest; persist();
    await refresh(); notice.value = '已找到原密钥记录。';
  } catch (error) { if (current !== generation) return; notice.value = error instanceof ApiError && error.problem.status === 404
    ? '尚未找到原请求；原操作可能仍在处理中，请稍后查询。' : '暂时无法确认原请求。'; }
  finally { if (current === generation) busy.value = false; }
}
async function changeCredential(item: Credential, action: 'replace' | 'revoke') {
  if (busy.value || pending.value.credentialRequest || (action === 'replace' && !secret.value)) return;
  if (action === 'revoke' && !globalThis.confirm('撤销后，引用该密钥的连接器将不可用。确定继续？')) return;
  busy.value = true; notice.value = ''; let sent = false; const current = generation;
  try {
    const token = await csrf(current); if (current !== generation) return; pending.value.credentialRequest = newRequestId(); persist();
    const header = { 'X-Space-Id': props.spaceId, 'X-CSRF-Token': token,
      'Idempotency-Key': pending.value.credentialRequest, 'If-Match': `"${item.revision}"` };
    sent = true;
    if (action === 'replace') {
      const value = secret.value; secret.value = '';
      const result = await api.POST('/api/v1/me/connector-credentials/{id}/replace', { params: { path: { id: item.id }, header }, body: { secret: value } });
      if (current !== generation) return; if (!result.data) throw new Error(); credentialId.value = result.data.id;
    } else {
      const result = await api.DELETE('/api/v1/me/connector-credentials/{id}', { params: { path: { id: item.id }, header } });
      if (current !== generation) return; if (!result.data) throw new Error(); if (credentialId.value === item.id) credentialId.value = '';
    }
    delete pending.value.credentialRequest; persist(); await refresh(); notice.value = action === 'replace' ? '密钥已替换。' : '密钥已撤销。';
  } catch (error) { if (current !== generation) return; secret.value = '';
    if (!sent || rejected(error)) { delete pending.value.credentialRequest; saveConnectorRequests(scope.value, pending.value); }
    notice.value = !sent ? '操作未发送，请检查连接与浏览器存储。' : rejected(error) ? '请求未被接受，请重新读取密钥状态。' : '结果尚未确认，请查询原密钥请求。'; }
  finally { if (current === generation) busy.value = false; }
}
async function createConnector() {
  if (busy.value || !endpoint.value || pending.value.configurationRequest) return;
  const credential = credentials.data.value?.items.find(item => item.id === credentialId.value && item.state === 'ACTIVE');
  if (auth.value !== 'NONE' && !credential) { notice.value = '请选择有效密钥。'; return; }
  busy.value = true; notice.value = ''; let sent = false; const current = generation;
  try {
    const token = await csrf(current); if (current !== generation) return; pending.value.configurationRequest = newRequestId(); persist();
    const body = { endpoint: endpoint.value, authKind: auth.value,
      apiKeyHeader: auth.value === 'API_KEY' ? apiKeyHeader.value : null,
      credentialId: auth.value === 'NONE' ? null : credential!.id, credentialRevision: auth.value === 'NONE' ? null : credential!.revision };
    const header = { 'X-Space-Id': props.spaceId, 'X-CSRF-Token': token, 'Idempotency-Key': pending.value.configurationRequest };
    sent = true;
    const result = editTarget.value
      ? await api.PUT('/api/v1/connectors/{id}', { body, params: { path: { id: editTarget.value.id }, header: { ...header, 'If-Match': `"${editTarget.value.revision}"` } } })
      : await api.POST('/api/v1/connectors', { body, params: { header } });
    if (current !== generation) return; if (!result.data) throw new Error(); selected.value = result.data; editTarget.value = undefined; detail.value = undefined;
    delete pending.value.configurationRequest; persist(); await refresh(); notice.value = '连接器已保存，请探测并确认版本。';
  } catch (error) { if (current !== generation) return; if (!sent || rejected(error)) { delete pending.value.configurationRequest; saveConnectorRequests(scope.value, pending.value); }
    notice.value = !sent ? '操作未发送，请检查连接与浏览器存储。' : rejected(error) ? '请求未被接受，请检查地址和认证配置。' : '结果尚未确认，请查询原请求。'; }
  finally { if (current === generation) busy.value = false; }
}
async function recoverConnector() {
  if (busy.value || !pending.value.configurationRequest) return; busy.value = true; notice.value = ''; const current = generation;
  try {
    const result = await api.GET('/api/v1/connectors/requests/{requestId}', { params: { path: { requestId: pending.value.configurationRequest }, header: { 'X-Space-Id': props.spaceId } } });
    if (current !== generation) return; if (!result.data) throw new Error(); selected.value = result.data; editTarget.value = undefined; delete pending.value.configurationRequest; persist();
    await refresh(); notice.value = '已找到原连接器。';
  } catch (error) { if (current !== generation) return; notice.value = error instanceof ApiError && error.problem.status === 404
    ? '尚未找到原请求；原操作可能仍在处理中，请稍后查询。' : '暂时无法确认原请求。'; }
  finally { if (current === generation) busy.value = false; }
}
function editConnector(item: Connector) {
  if (busy.value || pending.value.configurationRequest) return;
  editTarget.value = item; endpoint.value = item.endpoint; auth.value = item.authKind;
  credentialId.value = item.credentialId ?? '';
  apiKeyHeader.value = item.apiKeyHeader ?? 'X-API-Key';
}
async function startProbe() {
  if (busy.value || !selected.value || pending.value.probeRequest) return; busy.value = true; notice.value = ''; let sent = false; const current = generation;
  try {
    const token = await csrf(current); if (current !== generation) return; pending.value.probeRequest = newRequestId(); persist();
    sent = true; const result = await api.POST('/api/v1/connectors/{id}/test', { params: { path: { id: selected.value.id }, header: {
      'X-Space-Id': props.spaceId, 'X-CSRF-Token': token, 'Idempotency-Key': pending.value.probeRequest,
      'If-Match': `"${selected.value.revision}"` } } });
    if (current !== generation) return; if (!result.data) throw new Error(); probe.value = result.data; notice.value = '探测已排队，不会调用业务工具。';
  } catch (error) { if (current !== generation) return; if (!sent || rejected(error)) { delete pending.value.probeRequest; saveConnectorRequests(scope.value, pending.value); }
    notice.value = !sent ? '探测未发送，请检查连接与浏览器存储。' : rejected(error) ? '探测未被接受，请重新读取连接器。' : '探测结果未确认，请查询原请求。'; }
  finally { if (current === generation) busy.value = false; }
}
async function recoverProbe() {
  if (busy.value || !pending.value.probeRequest) return; busy.value = true; notice.value = ''; const current = generation;
  try {
    const result = await api.GET('/api/v1/connectors/tests/requests/{requestId}', { params: { path: { requestId: pending.value.probeRequest }, header: { 'X-Space-Id': props.spaceId } } });
    if (current !== generation) return; if (!result.data) throw new Error(); probe.value = result.data;
    if (selected.value?.id !== result.data.connectorId) {
      const connector = (await api.GET('/api/v1/connectors/{id}', { params: { path: { id: result.data.connectorId }, header: { 'X-Space-Id': props.spaceId } } })).data;
      if (current !== generation) return; selected.value = connector;
    }
    if (['READY', 'FAILED'].includes(result.data.state)) { delete pending.value.probeRequest; persist(); await refresh(); }
  } catch (error) { if (current !== generation) return; notice.value = error instanceof ApiError && error.problem.status === 404
    ? '尚未找到原探测；原操作可能仍在处理中，请稍后查询。' : '暂时无法确认原探测；不会自动重复探测。'; }
  finally { if (current === generation) busy.value = false; }
}
async function showVersion(versionId: string) {
  if (!selected.value) return; const current = generation; const connectorId = selected.value.id;
  try { const value = (await api.GET('/api/v1/connectors/{id}/versions/{versionId}', { params: { path: { id: connectorId, versionId }, header: { 'X-Space-Id': props.spaceId } } })).data;
    if (current === generation && selected.value?.id === connectorId) detail.value = value; }
  catch { if (current === generation) { detail.value = undefined; notice.value = '无法读取连接器版本。'; } }
}
async function sendActivation(request: NonNullable<ConnectorRequests['activation']>) {
  if (busy.value) return; busy.value = true; let sent = false; const current = generation;
  try {
    const token = await csrf(current); if (current !== generation) return; const header = { 'X-Space-Id': props.spaceId, 'X-CSRF-Token': token,
      'Idempotency-Key': request.requestId, 'If-Match': `"${request.revision}"` };
    sent = true;
    if (request.versionId) await api.POST('/api/v1/connectors/{id}/versions/{versionId}/enable', { params: { path: { id: request.connectorId, versionId: request.versionId }, header } });
    else await api.POST('/api/v1/connectors/{id}/disable', { params: { path: { id: request.connectorId }, header } });
    if (current !== generation) return; delete pending.value.activation; persist();
    if (selected.value?.id === request.connectorId && detail.value) await showVersion(detail.value.version.versionId);
    await refresh(); notice.value = request.versionId ? '版本已启用。' : '连接器已停用。';
  } catch (error) { if (current !== generation) return;
    if (sent && rejected(error)) { delete pending.value.activation; saveConnectorRequests(scope.value, pending.value); detail.value = undefined;
      notice.value = '请求未被接受，请重新读取连接器版本。'; }
    else notice.value = '结果尚未确认，可以使用原请求再次确认。';
  }
  finally { if (current === generation) busy.value = false; }
}
async function activate(enable: boolean) {
  if (!selected.value || !detail.value || busy.value || pending.value.activation) return;
  const request: NonNullable<ConnectorRequests['activation']> = { requestId: newRequestId(), connectorId: selected.value.id,
    ...(enable ? { versionId: detail.value.version.versionId } : {}), revision: detail.value.activation.revision };
  pending.value.activation = request;
  if (!saveConnectorRequests(scope.value, pending.value)) { delete pending.value.activation; notice.value = '无法保存请求身份，请检查浏览器存储。'; return; }
  await sendActivation(request);
}
</script>

<template>
  <section aria-labelledby="connector-heading">
    <h2 id="connector-heading">MCP 连接器</h2><p>仅支持公共 HTTPS。探测只发现工具，版本需确认后才能绑定；聊天自动使用将在后续阶段开放。</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <h3>连接器密钥</h3><label for="mcp-secret">新密钥</label>
    <input id="mcp-secret" v-model="secret" type="password" autocomplete="new-password" :disabled="busy">
    <button type="button" :disabled="busy || !secret || !!pending.credentialRequest" @click="createCredential">保存密钥</button>
    <button v-if="pending.credentialRequest" type="button" :disabled="busy" @click="recoverCredential">查询原密钥请求</button>
    <ul><li v-for="item in credentials.data.value?.items" :key="item.id">{{ item.id }} — {{ item.state }}
      <template v-if="item.state === 'ACTIVE'"><button type="button" :disabled="busy || !secret || !!pending.credentialRequest" @click="changeCredential(item, 'replace')">用输入的密钥替换</button>
        <button type="button" :disabled="busy || !!pending.credentialRequest" @click="changeCredential(item, 'revoke')">撤销</button></template>
    </li></ul>
    <button v-if="credentialCursor" type="button" @click="credentialCursor = undefined">密钥首页</button>
    <button v-if="credentials.data.value?.nextCursor" type="button" @click="credentialCursor = credentials.data.value?.nextCursor ?? undefined">下一页密钥</button>
    <h3>{{ editTarget ? '编辑连接器' : '新建连接器' }}</h3><label for="mcp-endpoint">HTTPS 地址</label><input id="mcp-endpoint" v-model="endpoint" type="url" :disabled="busy">
    <label for="mcp-auth">认证</label><select id="mcp-auth" v-model="auth" :disabled="busy"><option value="NONE">无</option><option value="BEARER">Bearer</option><option value="API_KEY">API Key</option></select>
    <template v-if="auth !== 'NONE'"><label for="mcp-key">密钥</label><select id="mcp-key" v-model="credentialId"><option value="">请选择</option><option v-for="item in credentials.data.value?.items.filter(c => c.state === 'ACTIVE')" :key="item.id" :value="item.id">{{ item.id }}</option></select></template>
    <template v-if="auth === 'API_KEY'"><label for="mcp-header">请求头</label><select id="mcp-header" v-model="apiKeyHeader"><option>X-API-Key</option><option>api-key</option></select></template>
    <button type="button" :disabled="busy || !endpoint || !!pending.configurationRequest" @click="createConnector">保存连接器</button>
    <button v-if="editTarget" type="button" :disabled="busy || !!pending.configurationRequest" @click="editTarget = undefined; endpoint = ''; auth = 'NONE'; credentialId = ''">取消编辑</button>
    <button v-if="pending.configurationRequest" type="button" :disabled="busy" @click="recoverConnector">查询原连接器请求</button>
    <p v-if="connectors.isError.value || credentials.isError.value" role="alert">无法读取连接器或密钥，请检查权限与连接。</p>
    <ul><li v-for="item in connectors.data.value?.items" :key="item.id"><button type="button" @click="selected = item; detail = undefined; probe = undefined">{{ item.endpoint }}</button></li></ul>
    <button v-if="connectorCursor" type="button" @click="connectorCursor = undefined">连接器首页</button>
    <button v-if="connectors.data.value?.nextCursor" type="button" @click="connectorCursor = connectors.data.value?.nextCursor ?? undefined">下一页连接器</button>
    <button v-if="pending.probeRequest" type="button" :disabled="busy" @click="recoverProbe">查询原探测</button>
    <button v-if="pending.activation" type="button" :disabled="busy" @click="sendActivation(pending.activation)">确认原版本操作</button>
    <template v-if="selected"><h3>已选连接器</h3><p>{{ selected.endpoint }}</p>
      <button type="button" :disabled="busy || !!pending.configurationRequest" @click="editConnector(selected)">编辑配置</button>
      <button type="button" :disabled="busy || !!pending.probeRequest" @click="startProbe">探测工具</button>
      <p v-if="probe" role="status">探测状态：{{ probe.state }}</p>
      <ul><li v-for="version in versions.data.value?.items" :key="version.versionId"><button type="button" @click="showVersion(version.versionId)">版本 {{ version.versionNumber }} — {{ version.state }}</button></li></ul>
      <button v-if="versionCursor" type="button" @click="versionCursor = undefined">版本首页</button>
      <button v-if="versions.data.value?.nextCursor" type="button" @click="versionCursor = versions.data.value?.nextCursor ?? undefined">下一页版本</button>
      <template v-if="detail"><p>工具 {{ detail.tools.length }} 项；{{ detail.eligible ? '可启用' : '配置或密钥已变化，需重新探测' }}</p>
        <details><summary>工具定义</summary><ul><li v-for="tool in detail.tools" :key="tool.name"><strong>{{ tool.name }}</strong> {{ tool.description }}<pre>{{ tool.schemaJson }}</pre></li></ul></details>
        <button v-if="detail.eligible && detail.activation.enabledVersionId !== detail.version.versionId" type="button" :disabled="busy || !!pending.activation" @click="activate(true)">确认并启用</button>
        <button v-if="detail.activation.enabledVersionId" type="button" :disabled="busy || !!pending.activation" @click="activate(false)">停用</button>
        <button v-if="detail.activation.enabledVersionId === detail.version.versionId && detail.eligible" type="button"
          @click="emit('candidate', { capabilityId: detail.version.connectorId, versionId: detail.version.versionId })">选择此版本用于绑定</button>
      </template>
    </template>
  </section>
</template>

<style scoped>
section { margin: 1rem 0; padding: 1rem; border: 1px solid var(--border-color, #888); border-radius: .5rem; }
input, select, button { margin: .3rem; max-width: 100%; } pre { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
