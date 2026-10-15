import { createPlatformClient } from './generated/client';
import { createRequestFetch } from './request';
import { sessionContext } from './session';

export const api = createPlatformClient({
  baseUrl: globalThis.location?.origin ?? 'http://localhost',
  fetch: createRequestFetch({
    context: sessionContext,
    onUnauthorized: () => globalThis.dispatchEvent?.(new Event('platform:session-expired')),
  }),
});
