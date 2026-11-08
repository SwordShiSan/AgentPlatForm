import { createRouter, createWebHistory } from 'vue-router';
import { api } from '../shared/api/client';

const chatEnabled = import.meta.env.VITE_CHAT_ENABLED === 'true';
const workspaceEnabled = import.meta.env.VITE_WORKSPACE_ENABLED === 'true';
const personalConfigEnabled = import.meta.env.VITE_PERSONAL_CONFIG_ENABLED === 'true';
const capabilitiesEnabled = import.meta.env.VITE_CAPABILITIES_ENABLED === 'true';
const knowledgeEnabled = import.meta.env.VITE_KNOWLEDGE_ENABLED === 'true';

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../features/identity/LoginPage.vue') },
    { path: '/app', redirect: chatEnabled ? '/app/chat' : '/app/settings/account' },
    { path: '/app/settings/account', component: () => import('../features/identity/AccountPage.vue'), meta: { requiresAuth: true } },
    ...(capabilitiesEnabled ? [{ path: '/app/settings/capabilities', component: () => import('../features/capabilities/CapabilityPage.vue'), meta: { requiresAuth: true } }] : []),
    ...(knowledgeEnabled ? [
      { path: '/app/knowledge', component: () => import('../features/knowledge/KnowledgePage.vue'), meta: { requiresAuth: true } },
      { path: '/app/memories', component: () => import('../features/knowledge/MemoryPage.vue'), meta: { requiresAuth: true } },
    ] : []),
    ...(personalConfigEnabled ? [
      { path: '/app/settings/general', component: () => import('../features/chat/PersonalConfigurationPage.vue'), meta: { requiresAuth: true } },
      { path: '/app/settings/models', component: () => import('../features/chat/PersonalConfigurationPage.vue'), meta: { requiresAuth: true } },
      { path: '/app/workspaces/:id/settings', component: () => import('../features/chat/PersonalConfigurationPage.vue'), meta: { requiresAuth: true } },
    ] : []),
    ...(workspaceEnabled ? [{ path: '/app/workspaces/:id?', component: () => import('../features/workspace/WorkspacePage.vue'), meta: { requiresAuth: true } }] : []),
    ...(workspaceEnabled ? [{ path: '/app/storage', component: () => import('../features/workspace/StoragePage.vue'), meta: { requiresAuth: true } }] : []),
    ...(chatEnabled ? [
      { path: '/app/chat/:id?', component: () => import('../features/chat/ChatPage.vue'), meta: { requiresAuth: true } },
      { path: '/', redirect: '/app/chat' },
    ] : [{ path: '/', component: () => import('../features/health/HealthPage.vue') }]),
    { path: '/status', component: () => import('../features/health/HealthPage.vue') },
    { path: '/forbidden', component: () => import('../features/health/ForbiddenPage.vue') },
    { path: '/:pathMatch(.*)*', component: () => import('../features/health/NotFoundPage.vue') },
  ],
});

router.beforeEach(async (to) => {
  if (!to.meta.requiresAuth) return;
  try { await api.GET('/api/v1/me'); }
  catch { return { path: '/login' }; }
});
