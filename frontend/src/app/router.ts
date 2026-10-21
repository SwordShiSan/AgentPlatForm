import { createRouter, createWebHistory } from 'vue-router';
import { api } from '../shared/api/client';

const chatEnabled = import.meta.env.VITE_CHAT_ENABLED === 'true';
const workspaceEnabled = import.meta.env.VITE_WORKSPACE_ENABLED === 'true';

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../features/identity/LoginPage.vue') },
    { path: '/app', redirect: chatEnabled ? '/app/chat' : '/app/settings/account' },
    { path: '/app/settings/account', component: () => import('../features/identity/AccountPage.vue'), meta: { requiresAuth: true } },
    ...(workspaceEnabled ? [{ path: '/app/workspaces/:id?', component: () => import('../features/workspace/WorkspacePage.vue'), meta: { requiresAuth: true } }] : []),
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
