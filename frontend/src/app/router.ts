import { createRouter, createWebHistory } from 'vue-router';

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: () => import('../features/health/HealthPage.vue') },
    { path: '/forbidden', component: () => import('../features/health/ForbiddenPage.vue') },
    { path: '/:pathMatch(.*)*', component: () => import('../features/health/NotFoundPage.vue') },
  ],
});
