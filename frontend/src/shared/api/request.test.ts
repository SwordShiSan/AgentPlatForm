import { describe, expect, it, vi } from 'vitest';
import { ApiError, createRequestFetch } from './request';

describe('request boundary', () => {
  it('adds explicit context, preserving supplied key and request body', async () => {
    const transport = vi.fn<typeof fetch>().mockResolvedValue(new Response('{}'));
    await createRequestFetch({ fetch: transport, context: () => ({ csrfToken: 'csrf', spaceId: 'space', idempotencyKey: 'context-key', ifMatch: '2' }) })(
      new Request('http://localhost/api/v1/example', { method: 'POST', headers: { 'Idempotency-Key': 'original-key' }, body: '{"value":1}' }),
    );
    const sent = transport.mock.calls[0][0] as Request;
    expect(sent.credentials).toBe('same-origin');
    expect(sent.headers.get('X-CSRF-Token')).toBe('csrf');
    expect(sent.headers.get('X-Space-Id')).toBe('space');
    expect(sent.headers.get('Idempotency-Key')).toBe('original-key');
    expect(sent.headers.get('If-Match')).toBe('2');
    expect(await sent.text()).toBe('{"value":1}');
  });

  it('bounds GET network retries to three total attempts', async () => {
    const transport = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('offline'));
    await expect(createRequestFetch({ fetch: transport })('http://localhost/api/v1/health')).rejects.toThrow('offline');
    expect(transport).toHaveBeenCalledTimes(3);
  });

  it('recovers a GET network failure', async () => {
    const transport = vi.fn<typeof fetch>().mockRejectedValueOnce(new TypeError('offline')).mockResolvedValue(new Response('{}'));
    await createRequestFetch({ fetch: transport })('http://localhost/api/v1/health');
    expect(transport).toHaveBeenCalledTimes(2);
  });

  it.each(['POST', 'PUT', 'PATCH', 'DELETE'])('never retries a failed %s', async (method) => {
    const transport = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('offline'));
    await expect(createRequestFetch({ fetch: transport })('http://localhost/api/v1/example', { method })).rejects.toThrow('offline');
    expect(transport).toHaveBeenCalledTimes(1);
  });

  it.each([401, 403, 404, 409, 412, 503])('surfaces Problem %s without HTTP retry', async (status) => {
    const onUnauthorized = vi.fn();
    const transport = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ type: 'urn:test', title: '失败', code: 'TEST_ERROR', traceId: 'trace', retryable: false, status }), { status, headers: { 'content-type': 'application/problem+json' } }));
    await expect(createRequestFetch({ fetch: transport, onUnauthorized })('http://localhost/api/v1/health')).rejects.toMatchObject({ problem: { status, code: 'TEST_ERROR', traceId: 'trace' } });
    expect(transport).toHaveBeenCalledTimes(1);
    expect(onUnauthorized).toHaveBeenCalledTimes(status === 401 ? 1 : 0);
  });

  it('does not expose gateway HTML as an error message', async () => {
    const transport = vi.fn<typeof fetch>().mockResolvedValue(new Response('<html>internal path and stack</html>', { status: 502 }));
    await expect(createRequestFetch({ fetch: transport })('http://localhost/api/v1/health')).rejects.toBeInstanceOf(ApiError);
  });

  it('never retries an aborted GET', async () => {
    const transport = vi.fn<typeof fetch>().mockRejectedValue(new DOMException('cancelled', 'AbortError'));
    await expect(createRequestFetch({ fetch: transport })('http://localhost/api/v1/health')).rejects.toThrow('cancelled');
    expect(transport).toHaveBeenCalledTimes(1);
  });
});
