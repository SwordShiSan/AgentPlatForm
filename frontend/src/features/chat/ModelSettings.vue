<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { api } from '../../shared/api/client';
import { setCsrf } from '../../shared/api/session';
import { ApiError } from '../../shared/api/request';
const props = defineProps<{ actor: string }>();
const cache = useQueryClient(); const secret = ref(''); const busy = ref(false); const notice = ref('');
const key = computed(() => ['chat', props.actor, 'credentials']);
const state = useQuery({ queryKey: key, enabled: computed(() => !!props.actor), queryFn: async () => (await api.GET('/api/v1/me/model-credentials')).data! });
async function update(revoke: boolean) {
  if (busy.value || !state.data.value) return;
  busy.value = true; notice.value = '';
  let value = secret.value; secret.value = '';
  try {
    const csrf = (await api.GET('/api/v1/auth/csrf')).data!; setCsrf(csrf.csrfToken);
    const params = { header: { 'If-Match': `"${state.data.value.version}"`, 'X-CSRF-Token': csrf.csrfToken } };
    if (revoke) await api.DELETE('/api/v1/me/model-credentials/deepseek', { params });
    else await api.PUT('/api/v1/me/model-credentials/deepseek', { params, body: { apiKey: value } });
    notice.value = revoke ? '个人密钥已撤销，后续请求将拒绝使用。' : '个人密钥已加密保存。旧密钥已撤销，旧任务不会自动切换。';
  } catch (error) { notice.value = error instanceof ApiError && error.problem.status === 412 ? '配置已被其他页面修改，请核对最新状态后重新输入。' : '操作未确认，请核对最新状态。密钥不会回显。'; }
  finally { value = ''; secret.value = ''; busy.value = false; await cache.invalidateQueries({ queryKey: key.value }); await cache.invalidateQueries({ queryKey: ['chat', props.actor, 'models'] }); await cache.invalidateQueries({ queryKey: ['chat', props.actor, 'configuration'] }); }
}
onBeforeUnmount(() => { secret.value = ''; });
</script>
<template>
  <details class="model-settings">
    <summary>模型密钥</summary>
    <p v-if="state.isError.value" role="alert">密钥状态暂时不可用。</p>
    <p v-else>个人密钥：{{ state.data.value?.userConfigured ? '已配置' : '未配置' }}；平台来源：{{ state.data.value?.platformAvailable ? '已授权' : '未授权' }}</p>
    <p>仅支持 DeepSeek。密钥加密保存，无法查看原文；个人来源失败不会使用平台密钥。</p>
    <form @submit.prevent="update(false)">
      <label for="model-key">新的 DeepSeek API key</label>
      <input id="model-key" v-model="secret" type="password" autocomplete="new-password" maxlength="4096" :disabled="busy" />
      <button :disabled="busy || !secret.trim() || !state.data.value" type="submit">保存或替换个人密钥</button>
      <button :disabled="busy || !state.data.value?.userConfigured" type="button" @click="update(true)">撤销个人密钥</button>
    </form>
    <p v-if="notice" role="status">{{ notice }}</p>
  </details>
</template>
