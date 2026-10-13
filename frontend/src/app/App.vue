<script setup lang="ts">
import { NConfigProvider } from 'naive-ui';
</script>

<template>
  <NConfigProvider :theme-overrides="{ common: { primaryColor: '#25634f', primaryColorHover: '#1b503e', borderRadius: '8px' } }">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <header class="site-header">
      <RouterLink to="/" class="brand" aria-label="Agent PlatForm 首页">
        <span class="brand-mark" aria-hidden="true">A</span>Agent PlatForm
      </RouterLink>
      <span class="stage-label">工程基线 · R0.1</span>
    </header>
    <RouterView />
  </NConfigProvider>
</template>
