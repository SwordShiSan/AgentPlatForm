import { onMounted, onUnmounted, ref, watch } from 'vue';

export type ThemePreference = 'light' | 'dark' | 'system';
export function useTheme() {
  let saved: string | null = null;
  try { saved = localStorage.getItem('platform-ui-theme'); } catch { /* Storage denial does not block UI. */ }
  const preference = ref<ThemePreference>(saved === 'light' || saved === 'dark' ? saved : 'system');
  const dark = ref(false);
  const media = window.matchMedia('(prefers-color-scheme: dark)');
  const apply = () => {
    dark.value = preference.value === 'dark' || (preference.value === 'system' && media.matches);
    document.documentElement.dataset.theme = dark.value ? 'dark' : 'light';
  };
  watch(preference, value => {
    try { localStorage.setItem('platform-ui-theme', value); } catch { /* UI-only preference. */ }
    apply();
  });
  onMounted(() => { media.addEventListener('change', apply); apply(); });
  onUnmounted(() => media.removeEventListener('change', apply));
  return { preference, dark };
}
