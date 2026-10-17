<script setup lang="ts">
import { useChatDrafts } from '../chat/drafts';
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { useQueryClient } from '@tanstack/vue-query';
import { api } from '../../shared/api/client';
import { ApiError } from '../../shared/api/request';
import { setCsrf } from '../../shared/api/session';

const router = useRouter();
const queryClient = useQueryClient();
const username = ref('');
const password = ref('');
const busy = ref(false);
const error = ref('');
async function login() {
  if (busy.value) return;
  busy.value = true; error.value = '';
  try {
    const csrf = await api.GET('/api/v1/auth/csrf');
    if (!csrf.data) throw new Error('CSRF unavailable');
    setCsrf(csrf.data.csrfToken);
    const result = await api.POST('/api/v1/auth/login', { body: { username: username.value, password: password.value }, params: { header: { 'X-CSRF-Token': csrf.data.csrfToken } } });
    if (!result.data) throw new Error('Login unavailable');
    setCsrf(result.data.csrfToken);
    useChatDrafts().clear(); queryClient.clear();
    password.value = '';
    await router.replace('/app/settings/account');
  } catch (failure) {
    password.value = '';
    error.value = failure instanceof ApiError && failure.problem.status === 401 ? '账号或密码不正确，请重试。' : failure instanceof ApiError && failure.problem.status === 429 ? '请求较多，请稍后再试。' : '暂时无法登录，请稍后重试。';
  } finally { busy.value = false; }
}
</script>

<template>
  <main id="main-content" class="page identity-page" tabindex="-1">
    <p class="eyebrow">AGENT PLATFORM</p>
    <h1>登录工作台</h1>
    <p class="intro">使用管理员为你开通的账号。</p>
    <form class="health-card identity-form" :aria-busy="busy" @submit.prevent="login">
      <label for="username">账号</label>
      <input id="username" v-model="username" name="username" autocomplete="username" required minlength="3" maxlength="64" :disabled="busy" />
      <label for="password">密码</label>
      <input id="password" v-model="password" name="password" type="password" autocomplete="current-password" required minlength="12" maxlength="128" :disabled="busy" />
      <p v-if="error" role="alert" class="identity-error">{{ error }}</p>
      <button type="submit" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button>
    </form>
  </main>
</template>
