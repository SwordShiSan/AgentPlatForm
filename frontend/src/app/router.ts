import { createRouter, createWebHistory } from 'vue-router';
import { api } from '../shared/api/client';

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../features/identity/LoginPage.vue') },
    { path: '/app', redirect: '/app/settings/account' },
    { path: '/app/settings/account', component: () => import('../features/identity/AccountPage.vue'), meta: { requiresAuth: true } },
    ...(import.meta.env.VITE_CHAT_ENABLED === 'true' ? [{ path: '/app/chat/:id?', component: () => import('../features/chat/ChatPage.vue'), meta: { requiresAuth: true } }] : []),
    { path: '/', component: () => import('../features/health/HealthPage.vue') },
    { path: '/forbidden', component: () => import('../features/health/ForbiddenPage.vue') },
    { path: '/:pathMatch(.*)*', component: () => import('../features/health/NotFoundPage.vue') },
  ],
});

router.beforeEach(async (to) => {
  if (!to.meta.requiresAuth) return;
  try { await api.GET('/api/v1/me'); }
  catch { return { path: '/login' }; }
});
