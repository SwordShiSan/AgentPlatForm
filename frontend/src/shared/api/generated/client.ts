/* Generated from contracts. Do not edit manually. */
import createClient from 'openapi-fetch';
import type { ClientOptions } from 'openapi-fetch';
import type { paths } from './http';

export function createPlatformClient(options: ClientOptions) {
  return createClient<paths>(options);
}
