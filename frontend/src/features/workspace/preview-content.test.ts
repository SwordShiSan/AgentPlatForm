import { describe, expect, it, vi } from 'vitest';
import { PDF_RANGE_BYTES, readPreviewBytes, SerialPreviewRanges, staticHtmlDocument } from './preview-content';

const rangeResponse = (start: number, end: number, total: number) => new Response(new Uint8Array(end - start), {
  status: 206, headers: { 'Content-Range': `bytes ${start}-${end - 1}/${total}`, 'Content-Length': String(end - start) },
});
describe('bounded preview reads', () => {
  it('serializes simultaneous PDF ranges and splits large reads into bounded physical requests', async () => {
    let active = 0; let peak = 0; const ranges: string[] = [];
    const total = PDF_RANGE_BYTES * 3;
    const fetcher = vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
      active++; peak = Math.max(peak, active);
      const range = new Headers(init?.headers).get('Range')!; ranges.push(range);
      expect(new Headers(init?.headers).get('X-Space-Id')).toBe('space');
      const [, begin, last] = /bytes=(\d+)-(\d+)/.exec(range)!;
      await new Promise(resolve => setTimeout(resolve, 2)); active--;
      return rangeResponse(Number(begin), Number(last) + 1, total);
    });
    const queue = new SerialPreviewRanges('/preview', 'space', total, new AbortController().signal, fetcher);
    const consumed: number[] = [];
    await Promise.all([queue.request(0, PDF_RANGE_BYTES * 2, (offset, value) => { consumed.push(offset); expect(value.byteLength).toBeLessThanOrEqual(PDF_RANGE_BYTES); }),
      queue.request(PDF_RANGE_BYTES * 2, total, offset => { consumed.push(offset); })]);
    expect(peak).toBe(1); expect(consumed).toEqual([0, PDF_RANGE_BYTES, PDF_RANGE_BYTES * 2]); expect(ranges).toHaveLength(3);
  });
  it('rejects a substituted range or an overlong/short body instead of passing bytes to PDF.js', async () => {
    const signal = new AbortController().signal;
    await expect(readPreviewBytes('/p', 's', 10, signal, vi.fn(async () => new Response(new Uint8Array(10), { status: 200 })),
      { begin: 0, end: 10, total: 10 })).rejects.toThrow('分段身份');
    await expect(readPreviewBytes('/p', 's', 10, signal, vi.fn(async () => new Response(new Uint8Array(11))))).rejects.toThrow('超过');
    await expect(readPreviewBytes('/p', 's', 10, signal, vi.fn(async () => new Response(new Uint8Array(9),
      { headers: { 'Content-Length': '10' } })))).rejects.toThrow('不完整');
  });
  it('deduplicates queued identical ranges and stops queued physical reads after close', async () => {
    const controller = new AbortController(); const fetcher = vi.fn(async () => rangeResponse(0, 10, 20));
    const queue = new SerialPreviewRanges('/p', 's', 20, controller.signal, fetcher);
    const consume = vi.fn(() => controller.abort());
    const first = queue.request(0, 10, consume); const duplicate = queue.request(0, 10, consume);
    const second = queue.request(10, 20, consume);
    await Promise.all([first, duplicate]); await expect(second).rejects.toThrow();
    expect(fetcher).toHaveBeenCalledTimes(1); expect(consume).toHaveBeenCalledTimes(1);
  });
  it('backs off only an explicit busy GET and never repeats an unauthorized read', async () => {
    vi.useFakeTimers(); const fetcher = vi.fn().mockResolvedValueOnce(new Response(null, { status: 423 }))
      .mockResolvedValueOnce(new Response(new Uint8Array(0)));
    const read = readPreviewBytes('/p', 's', 1, new AbortController().signal, fetcher);
    await vi.runAllTimersAsync(); await expect(read).resolves.toHaveLength(0); expect(fetcher).toHaveBeenCalledTimes(2); vi.useRealTimers();
    const forbidden = vi.fn().mockResolvedValue(new Response(null, { status: 403 }));
    await expect(readPreviewBytes('/p', 's', 1, new AbortController().signal, forbidden)).rejects.toThrow('不可用');
    expect(forbidden).toHaveBeenCalledTimes(1);
  });
  it('starts static HTML with an independent restrictive policy instead of granting script or origin access', () => {
    const html = staticHtmlDocument('<h1>safe rendered output</h1>');
    expect(html).toContain("default-src 'none'"); expect(html).toContain("form-action 'none'");
    expect(html.indexOf('Content-Security-Policy')).toBeLessThan(html.indexOf('<h1>'));
    expect(html).not.toContain('allow-scripts'); expect(html).not.toContain('allow-same-origin');
  });
});
