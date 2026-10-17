import { afterEach, expect, test, vi } from 'vitest';
import { newRequestId } from './request-id';

afterEach(() => vi.unstubAllGlobals());
test('HTTP-compatible crypto without randomUUID yields the RFC version and variant', () => {
  const fill = vi.fn((value: Uint8Array) => { value.forEach((_, index) => { value[index] = index; }); return value; });
  vi.stubGlobal('crypto', { getRandomValues: fill });
  expect(newRequestId()).toBe('00010203-0405-4607-8809-0a0b0c0d0e0f');
  expect(fill).toHaveBeenCalledTimes(1);
  expect(fill.mock.calls[0]![0]).toHaveLength(16);
});
test('missing cryptographic randomness fails explicitly instead of using Math.random', () => {
  vi.stubGlobal('crypto', undefined);
  expect(() => newRequestId()).toThrow('Cryptographic request identity source is unavailable');
});
