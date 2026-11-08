<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { api } from '../../shared/api/client';
import { newRequestId } from '../../shared/api/request-id';
import { setCsrf } from '../../shared/api/session';
import type { components } from '../../shared/api/generated/http';

type Base = components['schemas']['KnowledgeBase'];
type Document = components['schemas']['KnowledgeDocument'];
type FileItem = components['schemas']['FileItem'];
type Extraction = components['schemas']['DocumentExtraction'];
const cache = useQueryClient();
const me = useQuery({ queryKey: ['identity', 'me'], queryFn: async () => (await api.GET('/api/v1/me')).data! });
const actor = computed(() => me.data.value?.id ?? '');
const space = computed(() => me.data.value?.spaces.find(item => item.type === 'PERSONAL')?.id ?? '');
const bases = useQuery({ queryKey: computed(() => ['knowledge', actor.value, space.value, 'bases']), enabled: computed(() => !!space.value),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/knowledge-bases', { signal, params: { header: { 'X-Space-Id': space.value } } })).data! });
const workspaces = useQuery({ queryKey: computed(() => ['knowledge', actor.value, space.value, 'workspaces']), enabled: computed(() => !!space.value),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/workspaces', { signal, params: { header: { 'X-Space-Id': space.value }, query: { limit: 100 } } })).data! });
const newBaseName = ref(''); const selectedBase = ref(''); const workspaceId = ref(''); const selectedFile = ref('');
const searchText = ref(''); const searchBaseIds = ref<string[]>([]); const hits = ref<components['schemas']['KnowledgeSearchResult']>();
const extraction = ref<Extraction>(); const busy = ref(false); const error = ref(''); const notice = ref('');
watch(() => bases.data.value, rows => { if (!selectedBase.value && rows?.length) selectedBase.value = rows[0].id; });
watch(selectedBase, value => { if (value && !searchBaseIds.value.includes(value)) searchBaseIds.value = [value]; });
const files = useQuery({ queryKey: computed(() => ['knowledge', actor.value, space.value, 'files', workspaceId.value]), enabled: computed(() => !!space.value && !!workspaceId.value),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/workspaces/{id}/files', { signal, params: { path: { id: workspaceId.value }, header: { 'X-Space-Id': space.value }, query: { parent: '', limit: 100 } } })).data! });
const docs = useQuery({ queryKey: computed(() => ['knowledge', actor.value, space.value, 'documents', selectedBase.value]), enabled: computed(() => !!space.value && !!selectedBase.value),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/knowledge-bases/{id}/documents', { signal, params: { path: { id: selectedBase.value }, header: { 'X-Space-Id': space.value } } })).data!,
  refetchInterval: data => data.state.data?.some(item => ['QUEUED', 'BUILDING'].includes(item.state)) ? 1500 : false });
const fileChoices = computed<FileItem[]>(() => (files.data.value?.items ?? []).filter(item => item.kind === 'FILE' && item.versionId && item.collectionState === 'CONFIRMED'));
async function token() { const value = (await api.GET('/api/v1/auth/csrf')).data?.csrfToken; if (!value) throw Error('安全验证暂不可用'); setCsrf(value); return value; }
async function createBase() { if (!newBaseName.value.trim() || busy.value) return; busy.value = true; error.value = ''; notice.value = '';
  try { const csrf = await token(); const result = await api.POST('/api/v1/knowledge-bases', { body: { name: newBaseName.value.trim() }, params: { header: { 'X-Space-Id': space.value, 'X-CSRF-Token': csrf, 'Idempotency-Key': newRequestId() } } });
    if (!result.data) throw Error('知识库创建结果尚未确认'); newBaseName.value = ''; selectedBase.value = result.data.id; notice.value = '知识库已创建'; await cache.invalidateQueries({ queryKey: ['knowledge', actor.value, space.value, 'bases'] });
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '知识库创建失败'; } finally { busy.value = false; }
}
async function requestExtraction() { const file = fileChoices.value.find(item => item.id === selectedFile.value); if (!file?.versionId || busy.value) return; busy.value = true; error.value = ''; notice.value = '';
  try { const csrf = await token(); const result = await api.POST('/api/v1/file-extractions', { body: { fileId: file.id, versionId: file.versionId }, params: { header: { 'X-Space-Id': space.value, 'X-CSRF-Token': csrf, 'Idempotency-Key': newRequestId() } } });
    if (!result.data) throw Error('正文提取请求尚未确认'); extraction.value = result.data;
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '正文提取失败'; } finally { busy.value = false; }
}
const extractionQuery = useQuery({ queryKey: computed(() => ['knowledge', actor.value, space.value, 'extraction', extraction.value?.id]), enabled: computed(() => !!extraction.value?.id),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/file-extractions/{id}', { signal, params: { path: { id: extraction.value!.id }, header: { 'X-Space-Id': space.value } } })).data!,
  refetchInterval: data => ['QUEUED', 'RUNNING'].includes(data.state.data?.status ?? '') ? 1200 : false });
watch(extractionQuery.data, item => { if (item) extraction.value = item; });
async function indexDocument() { if (!selectedBase.value || !extraction.value || extraction.value.status !== 'READY' || busy.value) return; busy.value = true; error.value = ''; notice.value = '';
  try { const csrf = await token(); const result = await api.POST('/api/v1/knowledge-bases/{id}/documents', { params: { path: { id: selectedBase.value }, header: { 'X-Space-Id': space.value, 'X-CSRF-Token': csrf, 'Idempotency-Key': newRequestId() } }, body: { extractionId: extraction.value.id } });
    if (!result.data) throw Error('索引任务尚未确认'); notice.value = '索引已提交，状态将自动刷新'; extraction.value = undefined; await cache.invalidateQueries({ queryKey: ['knowledge', actor.value, space.value, 'documents', selectedBase.value] });
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '索引提交失败'; } finally { busy.value = false; }
}
async function search() { if (!searchText.value.trim() || !searchBaseIds.value.length || busy.value) return; busy.value = true; error.value = ''; notice.value = '';
  try { const csrf = await token(); const result = await api.POST('/api/v1/knowledge/search', { body: { query: searchText.value.trim(), baseIds: searchBaseIds.value, limit: 8, semanticRequired: false }, params: { header: { 'X-Space-Id': space.value, 'X-CSRF-Token': csrf } } });
    if (!result.data) throw Error('搜索结果暂不可用'); hits.value = result.data;
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '搜索失败'; } finally { busy.value = false; }
}
</script>

<template>
  <main id="main-content" class="page" tabindex="-1">
    <h1>知识库</h1>
    <p>索引和搜索仅用于知识管理；R6.3 不会自动把知识库内容加入普通聊天上下文。</p>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <section aria-labelledby="bases-title"><h2 id="bases-title">知识库</h2>
      <label>新知识库名称 <input v-model="newBaseName" maxlength="512"></label><button :disabled="busy" @click="createBase">创建</button>
      <label>当前知识库 <select v-model="selectedBase"><option v-for="item in bases.data.value ?? []" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
      <p v-if="bases.isPending.value" role="status">正在读取知识库…</p><p v-else-if="bases.isError.value" role="alert">知识库暂不可用。</p>
    </section>
    <section aria-labelledby="source-title"><h2 id="source-title">添加已封存文件版本</h2>
      <label>工作区 <select v-model="workspaceId"><option value="">选择工作区</option><option v-for="item in workspaces.data.value?.items ?? []" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
      <label>文件 <select v-model="selectedFile"><option value="">选择已确认文件</option><option v-for="item in fileChoices" :key="item.id" :value="item.id">{{ item.path }} · {{ item.versionId }}</option></select></label>
      <button :disabled="busy || !selectedBase || !selectedFile" @click="requestExtraction">提取并准备索引</button>
      <p v-if="extractionQuery.data.value">正文提取：{{ extractionQuery.data.value.status }}<span v-if="extractionQuery.data.value.errorCode"> · {{ extractionQuery.data.value.errorCode }}</span></p>
      <button v-if="extractionQuery.data.value?.status === 'READY'" :disabled="busy" @click="indexDocument">加入所选知识库</button>
      <p v-else-if="!fileChoices.length && workspaceId">没有可索引的已确认文件版本。</p>
    </section>
    <section aria-labelledby="documents-title"><h2 id="documents-title">索引状态</h2>
      <p v-if="docs.isPending.value" role="status">正在读取索引状态…</p><p v-else-if="docs.isError.value" role="alert">索引状态暂不可用。</p>
      <ul><li v-for="item in docs.data.value ?? []" :key="item.id">{{ item.name }} · {{ item.state }}<span v-if="item.activeIndexVersion"> · 版本 {{ item.activeIndexVersion }}</span><span v-if="item.versionId"> · 来源 {{ item.versionId }}</span></li></ul>
    </section>
    <section aria-labelledby="search-title"><h2 id="search-title">搜索</h2>
      <label>问题 <input v-model="searchText" maxlength="8192" @keydown.enter.prevent="search"></label>
      <label>知识库<select v-model="searchBaseIds" multiple size="3"><option v-for="item in bases.data.value ?? []" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
      <button :disabled="busy || !searchText.trim() || !searchBaseIds.length" @click="search">搜索</button>
      <p v-if="hits && !hits.semanticAvailable" role="status">语义检索不可用，当前结果来自可用检索方式：{{ hits.degradationReason }}</p>
      <p v-if="hits && !hits.hits.length">没有匹配内容。</p>
      <ol class="knowledge-results"><li v-for="(hit, index) in hits?.hits ?? []" :key="`${hit.documentId}:${index}`" class="knowledge-hit"><h3>{{ hit.name }}</h3><p>{{ hit.text }}</p><small>来源版本 {{ hit.versionId ?? hit.attachmentId }} · {{ hit.location }} · {{ hit.sourceSha256 }}</small></li></ol>
    </section>
  </main>
</template>

<style scoped>
.knowledge-results { min-width: 0; padding-inline-start: 1.5rem; }
.knowledge-hit { min-width: 0; overflow-wrap: anywhere; }
.knowledge-hit p, .knowledge-hit small { overflow-wrap: anywhere; }
.knowledge-hit small { display: block; }
</style>
