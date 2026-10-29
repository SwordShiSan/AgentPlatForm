<script setup lang="ts">
import { ref, watch } from 'vue';
import { NTree, NButton, type TreeOption } from 'naive-ui';
import { api } from '../../shared/api/client';
import type { components } from '../../shared/api/generated/http';
type Item = components['schemas']['FileItem'];
const props = defineProps<{ workspaceId: string; spaceId: string }>();
const emit = defineEmits<{ open: [Item] }>();
const nodes = ref<TreeOption[]>([]); const error = ref(''); let epoch = 0;
const entries = new Map<string, Item>();
async function page(path: string, cursor?: string): Promise<TreeOption[]> {
  const stamp = epoch;
  const result = await api.GET('/api/v1/workspaces/{id}/files', { params: { path: { id: props.workspaceId }, query: { parent: path, cursor, limit: 100 }, header: { 'X-Space-Id': props.spaceId } } });
  if (stamp !== epoch) return [];
  const rows: TreeOption[] = (result.data?.items ?? []).map(item => {
    entries.set(item.id, item); return { key: item.id, label: item.name, isLeaf: item.kind !== 'DIRECTORY', prefix: undefined };
  });
  if (result.data?.nextCursor) rows.push({ key: `page:${path}:${result.data.nextCursor}`, label: '加载更多…', isLeaf: true, pagePath: path, cursor: result.data.nextCursor });
  return rows;
}
async function load(node: TreeOption) { const item = entries.get(String(node.key)); if (item) node.children = await page(item.path); }
async function selected(keys: Array<string | number>, options: Array<TreeOption | null>) {
  const node = options[0]; if (!node) return;
  try {
    if (typeof node.pagePath === 'string') {
      const rows = await page(node.pagePath, String(node.cursor));
      const replace = (list: TreeOption[]) => { const i = list.findIndex(x => x.key === keys[0]); if (i >= 0) list.splice(i, 1, ...rows); else list.forEach(x => { if (x.children) replace(x.children); }); };
      replace(nodes.value);
    } else { const item = entries.get(String(node.key)); if (item?.kind === 'FILE') emit('open', item); }
  } catch { error.value = '目录读取失败，请重新读取。'; }
}
async function refresh() { epoch++; entries.clear(); error.value = ''; try { nodes.value = await page(''); } catch { error.value = '目录暂不可用，请检查连接或权限。'; } }
watch(() => `${props.spaceId}:${props.workspaceId}`, refresh, { immediate: true });
</script>
<template><section aria-label="文件目录树"><NButton @click="refresh">刷新目录树</NButton><p v-if="error" role="alert">{{ error }}</p><NTree block-line virtual-scroll :style="{ height: '300px' }" :data="nodes" :on-load="load" @update:selected-keys="selected" /></section></template>
