import type { components } from './generated/http';

export type Problem = components['schemas']['Problem'];

export class ApiError extends Error {
  constructor(public readonly problem: Problem) {
    super(problem.title);
    this.name = 'ApiError';
  }
}

export interface RequestContext {
  csrfToken?: string;
  spaceId?: string;
  idempotencyKey?: string;
  ifMatch?: string;
}

export function createRequestFetch(options: {
  fetch?: typeof globalThis.fetch;
  context?: () => RequestContext;
  onUnauthorized?: () => void;
} = {}): typeof globalThis.fetch {
  const transport = options.fetch ?? globalThis.fetch;
  return async (input, init) => {
    const request = new Request(input, init);
    const context = options.context?.() ?? {};
    const headers = new Headers(request.headers);
    const write = !['GET', 'HEAD', 'OPTIONS'].includes(request.method);
    const contextualHeaders = {
      'X-Space-Id': context.spaceId,
      'X-CSRF-Token': write ? context.csrfToken : undefined,
      'Idempotency-Key': write ? context.idempotencyKey : undefined,
      'If-Match': write ? context.ifMatch : undefined,
    };
    for (const [name, value] of Object.entries(contextualHeaders)) {
      if (value !== undefined && !headers.has(name)) headers.set(name, value);
    }
    const prepared = new Request(request, { headers, credentials: 'same-origin' });
    const attempts = prepared.method === 'GET' ? 3 : 1;
    let response: Response | undefined;
    for (let attempt = 0; attempt < attempts; attempt++) {
      try {
        response = await transport(prepared.clone());
        break;
      } catch (error) {
        if (prepared.signal.aborted || (error instanceof Error && error.name === 'AbortError') || attempt === attempts - 1) throw error;
      }
    }
    if (!response) throw new Error('No response');
    if (response.status === 401) options.onUnauthorized?.();
    if (!response.ok) {
      let body: Partial<Problem> = {};
      if (response.headers.get('content-type')?.includes('application/problem+json')) {
        try { body = await response.clone().json(); } catch { /* Fall back to a safe HTTP error. */ }
      }
      throw new ApiError({
        type: body.type ?? 'urn:agentplatform:error:http-error',
        title: body.title ?? `请求失败 (${response.status})`,
        status: response.status,
        code: body.code ?? 'HTTP_ERROR',
        traceId: body.traceId ?? '',
        retryable: body.retryable ?? false,
        ...(body.detail ? { detail: body.detail } : {}),
        ...(body.resourceId ? { resourceId: body.resourceId } : {}),
      });
    }
    return response;
  };
}
