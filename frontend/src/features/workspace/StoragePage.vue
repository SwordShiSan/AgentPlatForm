<script setup lang="ts">
import { computed, ref } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { api } from '../../shared/api/client';
const me = useQuery({ queryKey: ['identity', 'me'], queryFn: async () => (await api.GET('/api/v1/me')).data! });
const actor = computed(() => me.data.value?.id ?? '');
const space = computed(() => me.data.value?.spaces.find(item => item.type === 'PERSONAL')?.id ?? '');
const usage = useQuery({ queryKey: computed(() => ['storage', actor.value, space.value]), enabled: computed(() => !!space.value),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/me/storage-usage', { signal, params: { header: { 'X-Space-Id': space.value } } })).data! });
const cursor = ref<string>();
const workspaces = useQuery({ queryKey: computed(() => ['storage', actor.value, space.value, 'workspaces', cursor.value]), enabled: computed(() => !!space.value),
  queryFn: async ({ signal }) => (await api.GET('/api/v1/workspaces', { signal, params: { query: { cursor: cursor.value, limit: 100 }, header: { 'X-Space-Id': space.value } } })).data! });
const categories: Record<string, string> = { 'file-version': '文件及历史版本', 'chat-attachment': '聊天附件原件', preview: '派生预览', 'text-extraction': '提取正文' };
const bytes = (value: number) => `${(value / 1024 / 1024).toLocaleString(undefined, { maximumFractionDigits: 2 })} MiB`;
</script>
<template>
  <main id="main-content" class="page" tabindex="-1">
    <h1>数据与存储</h1>
    <p>对象逻辑额度统一计入文件历史、附件、预览和提取正文；它不是物理磁盘容量承诺。相同版本的引用不会重复计量。</p>
    <p v-if="usage.isPending.value" role="status">正在读取存储用量…</p>
    <p v-if="usage.isError.value" role="alert">用量暂不可用。<button @click="usage.refetch()">重新读取</button></p>
    <section v-if="usage.data.value" aria-label="对象用量">
      <dl><dt>统一额度</dt><dd>{{ bytes(usage.data.value.objectLimitBytes) }}</dd><dt>已用</dt><dd>{{ bytes(usage.data.value.usedBytes) }}</dd>
        <dt>已预留</dt><dd>{{ bytes(usage.data.value.reservedBytes) }}</dd><dt>删除工作副本后保留的历史</dt><dd>{{ bytes(usage.data.value.retainedBytes) }}（包含在已用中）</dd></dl>
      <ul><li v-for="item in usage.data.value.categories" :key="item.category">{{ categories[item.category] }}：{{ bytes(item.bytes) }} · {{ item.objects }} 个对象</li></ul>
    </section>
    <section aria-label="回收站与恢复"><h2>已删除的工作副本</h2><p>删除记录至少保留30天；恢复创建新版本。此处不提供永久删除或对象垃圾回收。</p>
      <p v-if="workspaces.isError.value" role="alert">工作区列表暂不可用。</p>
      <ul><li v-for="item in workspaces.data.value?.items" :key="item.id"><RouterLink :to="`/app/workspaces/${item.id}?deleted=1`">{{ item.name }} · 已删除文件与恢复</RouterLink></li></ul>
      <button v-if="workspaces.data.value?.nextCursor" @click="cursor = workspaces.data.value.nextCursor ?? undefined">下一页工作区</button>
      <button v-if="cursor" @click="cursor = undefined">返回第一页</button>
    </section>
    <RouterLink to="/app/chat">返回工作台</RouterLink>
  </main>
</template>
