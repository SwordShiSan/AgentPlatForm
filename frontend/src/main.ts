import { useChatDrafts } from './features/chat/drafts';
import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { setCsrf } from './shared/api/session';
import App from './app/App.vue';
import { router } from './app/router';
import './shared/ui/tokens.css';
import './shared/ui/base.css';

const pinia = createPinia();
const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
globalThis.addEventListener('platform:session-expired', () => {
  useChatDrafts(pinia).clear();
  setCsrf(undefined);
  queryClient.clear();
  if (router.currentRoute.value.meta.requiresAuth) void router.replace('/login');
});

createApp(App)
  .use(pinia)
  .use(VueQueryPlugin, { queryClient })
  .use(router)
  .mount('#app');
