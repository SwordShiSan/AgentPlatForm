<script setup lang="ts">
import { computed } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { NButton, NSkeleton } from 'naive-ui';
import { api } from '../../shared/api/client';
import { useViewPreference } from '../../app/view-preference';

const view = useViewPreference();
const chatEnabled = import.meta.env.VITE_CHAT_ENABLED === 'true';
const health = useQuery({
  queryKey: ['platform', 'shell-health'],
  queryFn: async () => {
    const { data } = await api.GET('/api/v1/health');
    if (!data || data.status !== 'UP') throw new Error('Health response is invalid');
    return data;
  },
  staleTime: 0,
  retry: false,
});
const isHealthy = computed(() => !health.isError.value && health.data.value?.status === 'UP');
</script>

<template>
  <main id="main-content" class="page" tabindex="-1">
    <p class="eyebrow">AGENT PLATFORM</p>
    <h1>{{ chatEnabled ? '应用连接状态' : '从可靠的基础开始。' }}</h1>
    <p class="intro">{{ chatEnabled ? '查看应用连接状态，使用页头导航进入聊天或查看账号。' : '连接应用，确认状态。智能体工作台将在后续阶段逐步开放。' }}</p>

    <section class="health-card" aria-labelledby="health-heading" :aria-busy="health.isFetching.value">
      <div class="card-heading">
        <div>
          <p class="eyebrow">APPLICATION</p>
          <h2 id="health-heading">应用连接</h2>
        </div>
        <span class="status-pill" :class="{ healthy: isHealthy, unavailable: health.isError.value }" role="status" aria-live="polite">
          <span class="status-dot" aria-hidden="true"></span>
          {{ health.isFetching.value ? '正在检查' : health.isError.value ? '暂时不可用' : isHealthy ? '连接正常' : '等待检查' }}
        </span>
      </div>
      <NSkeleton v-if="health.isPending.value" text :repeat="2" />
      <div v-else-if="health.isError.value" role="alert" class="status-content">
        <h3>暂时无法连接应用</h3>
        <p>请检查服务是否启动，然后重新检查连接。</p>
      </div>
      <div v-else class="status-content">
        <h3>应用已就绪</h3>
        <p>{{ chatEnabled ? '普通聊天和个人空间已开放，登录后即可使用。' : '当前仅开放基础连接检查。' }}</p>
      </div>
      <div class="card-actions">
        <NButton type="primary" :loading="health.isFetching.value" @click="health.refetch()">重新检查</NButton>
        <NButton quaternary :aria-expanded="view.showDetails" aria-controls="connection-details" @click="view.showDetails = !view.showDetails">连接详情</NButton>
      </div>
      <dl v-if="view.showDetails" id="connection-details" class="details">
        <div><dt>健康接口</dt><dd><code>GET /api/v1/health</code></dd></div>
        <div><dt>响应</dt><dd>{{ isHealthy ? 'UP' : '未确认' }}</dd></div>
      </dl>
    </section>

    <footer class="page-footer">{{ chatEnabled ? '连接检查不展示内部依赖和运行指标。' : '工程基线已建立后，再逐步接入模型与工作区。' }}</footer>
  </main>
</template>
