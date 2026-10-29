import { createRequestFetch } from '../../shared/api/request';
import { sessionContext } from '../../shared/api/session';

export const PDF_RANGE_BYTES = 256 * 1024;
export const MAX_PREVIEW_OBJECT_BYTES = 100 * 1024 * 1024;
const transport = createRequestFetch({ context: sessionContext,
  onUnauthorized: () => globalThis.dispatchEvent(new Event('platform:session-expired')) });

async function pause(ms: number, signal: AbortSignal) {
  signal.throwIfAborted();
  await new Promise<void>((resolve, reject) => {
    const aborted = () => { clearTimeout(timer); reject(signal.reason ?? new DOMException('已停止读取', 'AbortError')); };
    const timer = setTimeout(() => { signal.removeEventListener('abort', aborted); resolve(); }, ms);
    signal.addEventListener('abort', aborted, { once: true });
  });
}

/** A preview never retains a response outside the component, Query or browser storage. */
export async function readPreviewBytes(url: string, space: string, limit: number, signal: AbortSignal,
  fetcher: typeof fetch = transport, range?: { begin: number; end: number; total: number }) {
  if (!Number.isSafeInteger(limit) || limit < 0 || limit > MAX_PREVIEW_OBJECT_BYTES) throw new Error('预览长度超出范围');
  const headers: Record<string, string> = { 'X-Space-Id': space };
  if (range) headers.Range = `bytes=${range.begin}-${range.end - 1}`;
  let response: Response | undefined;
  for (let attempt = 0; attempt < 6; attempt++) {
    signal.throwIfAborted();
    try { response = await fetcher(url, { credentials: 'same-origin', headers, signal, cache: 'no-store' }); }
    catch (error) {
      const status = (error as { problem?: { status?: number } }).problem?.status;
      if (status !== 423 || attempt === 5) throw error;
    }
    if (response?.status !== 423) { if (response) break; }
    else { await response.body?.cancel(); response = undefined; }
    if (attempt === 5) throw new Error('文件传输忙，请稍后重新读取');
    await pause(Math.min(1000, (attempt + 1) * 250), signal);
  }
  if (!response?.ok) throw new Error('预览内容暂不可用，请下载原版本');
  if (range && (response.status !== 206 || response.headers.get('Content-Range') !== `bytes ${range.begin}-${range.end - 1}/${range.total}`)) {
    await response.body?.cancel(); throw new Error('预览分段身份不一致');
  }
  const declared = response.headers.get('Content-Length');
  if (declared !== null && (!/^\d+$/.test(declared) || Number(declared) > limit)) {
    await response.body?.cancel(); throw new Error('预览内容超过允许长度');
  }
  const reader = response.body?.getReader(); if (!reader) throw new Error('预览内容不可读取');
  const chunks: Uint8Array<ArrayBuffer>[] = []; let bytes = 0;
  try {
    for (;;) {
      signal.throwIfAborted(); const item = await reader.read(); if (item.done) break;
      bytes += item.value.byteLength;
      if (bytes > limit) throw new Error('预览内容超过允许长度');
      chunks.push(new Uint8Array(item.value));
    }
    if (range && bytes !== range.end - range.begin || declared !== null && bytes !== Number(declared)) throw new Error('预览内容传输不完整');
    const value = new Uint8Array(bytes); let offset = 0;
    for (const chunk of chunks) { value.set(chunk, offset); offset += chunk.byteLength; }
    return value;
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}

/** PDF.js may request concurrent ranges; the global transfer permit allows one physical reader. */
export class SerialPreviewRanges {
  private pending = Promise.resolve();
  private readonly queued = new Set<string>();
  constructor(private readonly url: string, private readonly space: string, private readonly total: number,
    private readonly signal: AbortSignal, private readonly fetcher: typeof fetch = transport) {
    if (!Number.isSafeInteger(total) || total < 1 || total > MAX_PREVIEW_OBJECT_BYTES) throw new Error('PDF长度超出范围');
  }
  request(begin: number, end: number, consume: (offset: number, value: Uint8Array) => void): Promise<void> {
    if (!Number.isSafeInteger(begin) || !Number.isSafeInteger(end) || begin < 0 || end <= begin || end > this.total) return Promise.reject(new Error('PDF分段范围无效'));
    const key = `${begin}:${end}`;
    if (this.queued.has(key)) return this.pending;
    this.queued.add(key);
    const next = this.pending.then(async () => {
      for (let first = begin; first < end; first += PDF_RANGE_BYTES) {
        this.signal.throwIfAborted(); const last = Math.min(end, first + PDF_RANGE_BYTES);
        const chunk = await readPreviewBytes(this.url, this.space, last - first, this.signal, this.fetcher,
          { begin: first, end: last, total: this.total });
        this.signal.throwIfAborted(); consume(first, chunk);
      }
    }).finally(() => this.queued.delete(key));
    this.pending = next.catch(() => {}); return next;
  }
}

export function staticHtmlDocument(body: string) {
  // The server allowlist is authoritative. This independent opaque iframe policy also blocks remote content.
  return `<!doctype html><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; font-src 'none'; form-action 'none'; base-uri 'none'"><style>body{font:16px/1.6 system-ui;margin:1rem;overflow-wrap:anywhere}table{border-collapse:collapse;max-width:100%}td,th{border:1px solid #999;padding:.4rem}pre{white-space:pre-wrap}</style>${body}`;
}
