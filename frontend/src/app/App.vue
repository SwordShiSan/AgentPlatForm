<script setup lang="ts">
import { darkTheme, NConfigProvider } from 'naive-ui';
import { useTheme } from './theme';
const { preference, dark } = useTheme();
const chatEnabled = import.meta.env.VITE_CHAT_ENABLED === 'true';
const workspaceEnabled = import.meta.env.VITE_WORKSPACE_ENABLED === 'true';
</script>

<template>
  <NConfigProvider :theme="dark ? darkTheme : null" :theme-overrides="{ common: { primaryColor: dark ? '#91b4ff' : '#2563eb', primaryColorHover: dark ? '#afc8ff' : '#1d4ed8', borderRadius: '8px' }, Button: { textColorPrimary: dark ? '#17191d' : '#ffffff' } }">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <header class="site-header">
      <RouterLink to="/" class="brand" aria-label="Agent PlatForm 首页">
        <span class="brand-mark" aria-hidden="true">A</span>Agent PlatForm
      </RouterLink>
      <nav class="site-nav" aria-label="主要导航">
        <RouterLink v-if="chatEnabled" to="/app/chat">聊天</RouterLink>
        <RouterLink v-if="workspaceEnabled" to="/app/workspaces">工作区</RouterLink>
        <RouterLink v-if="chatEnabled" to="/app/settings/account">账号</RouterLink>
        <RouterLink to="/status">连接状态</RouterLink>
      </nav>
      <label class="theme-control">主题
        <select v-model="preference" aria-label="主题"><option value="system">跟随系统</option><option value="light">浅色</option><option value="dark">深色</option></select>
      </label>
    </header>
    <RouterView />
  </NConfigProvider>
</template>
