import { createRouter, createWebHistory } from 'vue-router';

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: () => import('../features/health/HealthPage.vue') },
    { path: '/:pathMatch(.*)*', component: () => import('../features/health/NotFoundPage.vue') },
  ],
});
