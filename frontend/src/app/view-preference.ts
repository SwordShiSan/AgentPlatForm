import { defineStore } from 'pinia';

export const useViewPreference = defineStore('view-preference', {
  state: () => ({ showDetails: false }),
});
