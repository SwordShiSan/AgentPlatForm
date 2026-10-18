<script setup lang="ts">
import { useChatDrafts } from '../chat/drafts';
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { api } from '../../shared/api/client';
import { setCsrf } from '../../shared/api/session';

const router = useRouter();
const queryClient = useQueryClient();
const error = ref('');
const busy = ref(false);
const chatEnabled = import.meta.env.VITE_CHAT_ENABLED === 'true';
const me = useQuery({ queryKey: ['identity', 'me'], queryFn: async () => {
  const result = await api.GET('/api/v1/me');
  if (!result.data) throw new Error('Account unavailable');
  return result.data;
} });
async function logout() {
  if (busy.value) return;
  busy.value = true; error.value = '';
  try {
    const csrf = await api.GET('/api/v1/auth/csrf');
    if (!csrf.data) throw new Error('CSRF unavailable');
    setCsrf(csrf.data.csrfToken);
    await api.POST('/api/v1/auth/logout', { params: { header: { 'X-CSRF-Token': csrf.data.csrfToken } } });
    setCsrf(undefined); useChatDrafts().clear(); queryClient.clear();
    await router.replace('/login');
  } catch { error.value = '暂时无法退出，请重试。'; }
  finally { busy.value = false; }
}
</script>

<template>
  <main id="main-content" class="account-layout" tabindex="-1">
    <aside class="account-sidebar" aria-label="账号导航">
      <p class="eyebrow">个人空间</p>
      <RouterLink to="/app/settings/account" aria-current="page">账号信息</RouterLink>
      <RouterLink v-if="chatEnabled" to="/app/chat">普通聊天</RouterLink>
      <div v-if="me.data.value" class="user-menu">
        <span>{{ me.data.value.username }}</span>
        <button :disabled="busy" @click="logout">退出登录</button>
      </div>
    </aside>
    <section class="account-content" aria-labelledby="account-title" :aria-busy="me.isFetching.value">
      <h1 id="account-title">我的账号</h1>
      <p v-if="me.isPending.value" role="status">正在读取账号…</p>
      <p v-else-if="me.isError.value" role="alert">账号暂时不可用，请刷新重试。</p>
      <div v-else-if="me.data.value" class="health-card">
        <dl class="account-details"><dt>账号</dt><dd>{{ me.data.value.username }}</dd><dt>空间</dt><dd>个人空间</dd></dl>
        <p class="intro">{{ chatEnabled ? '你的个人空间用于保存对话和个人配置。' : '你的个人空间已建立。后续功能将逐步开放。' }}</p>
      </div>
      <p v-if="error" role="alert" class="identity-error">{{ error }}</p>
    </section>
  </main>
</template>
