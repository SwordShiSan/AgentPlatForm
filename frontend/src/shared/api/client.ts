import { createPlatformClient } from './generated/client';
import { createRequestFetch } from './request';

export const api = createPlatformClient({
  baseUrl: globalThis.location?.origin ?? 'http://localhost',
  fetch: createRequestFetch({
    onUnauthorized: () => globalThis.dispatchEvent?.(new Event('platform:session-expired')),
  }),
});
