<script setup lang="ts">
import { computed, ref } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { api } from '../../shared/api/client';
import { newRequestId } from '../../shared/api/request-id';
import { setCsrf } from '../../shared/api/session';
import type { components } from '../../shared/api/generated/http';

type Memory = components['schemas']['PersonalMemory'];
const cache = useQueryClient();
const me = useQuery({ queryKey: ['identity', 'me'], queryFn: async () => (await api.GET('/api/v1/me')).data! });
const actor = computed(() => me.data.value?.id ?? '');
const space = computed(() => me.data.value?.spaces.find(item => item.type === 'PERSONAL')?.id ?? '');
const status = ref('ALL'); const category = ref<'PREFERENCE' | 'FACT' | 'PROCEDURE'>('FACT'); const topic = ref(''); const content = ref('');
const scopeKind = ref<'PERSONAL' | 'WORKSPACE'>('PERSONAL'); const workspaceId = ref(''); const error = ref(''); const notice = ref(''); const busy = ref(false);
const memories = useQuery({ queryKey: computed(() => ['memories', actor.value, space.value, status.value]), enabled: computed(() => !!space.value),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/me/memories', { signal, params: { header: { 'X-Space-Id': space.value }, query: { status: status.value as 'ALL' | 'PENDING' | 'ACTIVE' | 'REJECTED' | 'SUPERSEDED' | 'REVIEW_REQUIRED' | 'EXPIRED' | 'DELETED' } } })).data! });
const workspaces = useQuery({ queryKey: computed(() => ['memories', actor.value, space.value, 'workspaces']), enabled: computed(() => !!space.value),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/workspaces', { signal, params: { header: { 'X-Space-Id': space.value }, query: { limit: 100 } } })).data! });
async function csrf() { const value = (await api.GET('/api/v1/auth/csrf')).data?.csrfToken; if (!value) throw Error('安全验证暂不可用'); setCsrf(value); return value; }
async function create() { if (busy.value) return; busy.value = true; error.value = ''; notice.value = '';
  try { const token = await csrf(); const body = { scopeKind: scopeKind.value, workspaceId: scopeKind.value === 'WORKSPACE' ? workspaceId.value : null, category: category.value, topicKey: topic.value.trim(), content: content.value.trim() };
    const result = await api.POST('/api/v1/me/memories/candidates', { body, params: { header: { 'X-Space-Id': space.value, 'X-CSRF-Token': token, 'Idempotency-Key': newRequestId() } } });
    if (!result.data) throw Error('候选创建结果尚未确认'); content.value = ''; topic.value = ''; scopeKind.value = 'PERSONAL'; notice.value = '候选已创建，确认前不会进入记忆召回'; await cache.invalidateQueries({ queryKey: ['memories', actor.value, space.value] });
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '候选创建失败'; } finally { busy.value = false; }
}
async function action(item: Memory, action: 'CONFIRM' | 'REJECT' | 'DELETE' | 'EDIT' | 'SUPERSEDE') { if (busy.value) return; busy.value = true; error.value = ''; notice.value = '';
  try { const token = await csrf(); let body: components['schemas']['ChangePersonalMemory'] = { action, expectedRevision: item.revision };
    if (action === 'EDIT' || action === 'SUPERSEDE') { const nextTopic = window.prompt('主题', item.topicKey); if (nextTopic === null) return; const nextContent = window.prompt('内容', item.content); if (nextContent === null) return;
      body = { ...body, category: item.category, topicKey: nextTopic, content: nextContent }; }
    const result = await api.POST('/api/v1/me/memories/{id}/actions', { params: { path: { id: item.id }, header: { 'X-Space-Id': space.value, 'X-CSRF-Token': token, 'Idempotency-Key': newRequestId() } }, body });
    if (!result.data) throw Error('操作结果尚未确认'); notice.value = action === 'CONFIRM' ? '记忆已确认' : action === 'REJECT' ? '候选已拒绝' : action === 'DELETE' ? '记忆已删除' : '记忆已更新'; await cache.invalidateQueries({ queryKey: ['memories', actor.value, space.value] });
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '操作失败，请重新读取状态后处理'; } finally { busy.value = false; }
}
</script>

<template>
  <main id="main-content" class="page" tabindex="-1">
    <h1>个人记忆</h1>
    <p>只有你明确创建并确认的记忆才会成为候选。R6.3 暂不自动注入普通聊天上下文。</p>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <section aria-labelledby="candidate-title"><h2 id="candidate-title">创建待审核候选</h2>
      <label>范围 <select v-model="scopeKind"><option value="PERSONAL">个人</option><option value="WORKSPACE">工作区</option></select></label>
      <label v-if="scopeKind === 'WORKSPACE'">工作区 <select v-model="workspaceId"><option value="">选择本人有权访问的工作区</option><option v-for="item in workspaces.data.value?.items ?? []" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
      <label>类别 <select v-model="category"><option value="PREFERENCE">偏好</option><option value="FACT">事实</option><option value="PROCEDURE">流程</option></select></label>
      <label>主题 <input v-model="topic" maxlength="256"></label><label>内容 <textarea v-model="content" maxlength="8192" rows="4"></textarea></label>
      <button :disabled="busy || !topic.trim() || !content.trim() || (scopeKind === 'WORKSPACE' && !workspaceId)" @click="create">提交审核</button>
    </section>
    <section aria-labelledby="memory-list"><h2 id="memory-list">记忆条目</h2>
      <label>状态 <select v-model="status"><option v-for="value in ['ALL','PENDING','ACTIVE','REJECTED','SUPERSEDED','REVIEW_REQUIRED','EXPIRED','DELETED']" :key="value" :value="value">{{ value }}</option></select></label>
      <p v-if="memories.isPending.value" role="status">正在读取记忆…</p><p v-else-if="memories.isError.value" role="alert">记忆暂不可用。</p><p v-else-if="!memories.data.value?.length">暂无记忆。</p>
      <article v-for="item in memories.data.value ?? []" :key="item.id" class="memory-card"><h3>{{ item.topicKey }} · {{ item.category }}</h3><p>{{ item.content }}</p><small>{{ item.scopeKind }}<span v-if="item.workspaceId"> · 工作区 {{ item.workspaceId }}</span> · {{ item.status }} · 修订 {{ item.revision }} · 到期 {{ item.expiresAt }}</small>
        <div class="actions"><button v-if="item.status === 'PENDING'" :disabled="busy" @click="action(item,'CONFIRM')">确认</button><button v-if="item.status === 'PENDING'" :disabled="busy" @click="action(item,'REJECT')">拒绝</button>
          <button v-if="['PENDING','ACTIVE','REVIEW_REQUIRED'].includes(item.status)" :disabled="busy" @click="action(item,'EDIT')">编辑</button><button v-if="['PENDING','ACTIVE','REVIEW_REQUIRED'].includes(item.status)" :disabled="busy" @click="action(item,'SUPERSEDE')">替代</button><button v-if="!['DELETED','SUPERSEDED'].includes(item.status)" :disabled="busy" @click="action(item,'DELETE')">删除</button></div>
      </article>
    </section>
  </main>
</template>

<style scoped>.memory-card{padding:1rem;margin-block:1rem;border:1px solid var(--border,#8884);border-radius:.5rem}.actions{display:flex;flex-wrap:wrap;gap:.5rem;margin-top:.75rem}</style>
