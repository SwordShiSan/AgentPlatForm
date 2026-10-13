import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { VueQueryPlugin } from '@tanstack/vue-query';
import App from './app/App.vue';
import { router } from './app/router';
import './shared/ui/tokens.css';
import './shared/ui/base.css';

createApp(App)
  .use(createPinia())
  .use(VueQueryPlugin, { queryClientConfig: { defaultOptions: { queries: { retry: false } } } })
  .use(router)
  .mount('#app');
