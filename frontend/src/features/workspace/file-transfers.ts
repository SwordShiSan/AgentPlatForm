import { sha256 } from '@noble/hashes/sha2.js';
import { bytesToHex } from '@noble/hashes/utils.js';
import { ApiError } from '../../shared/api/request';
import type { components } from '../../shared/api/generated/http';

export const MAX_UPLOAD_BYTES = 100 * 1024 * 1024;
export const MAX_PREVIEW_BYTES = 2 * 1024 * 1024;
export async function fileHash(file: Blob, signal?: AbortSignal, progress?: (value: number) => void) {
  if (file.size > MAX_UPLOAD_BYTES) throw new Error('文件不能超过 100 MiB');
  const hash = sha256.create();
  try {
    for (let offset = 0; offset < file.size; offset += 1024 * 1024) {
      signal?.throwIfAborted();
      hash.update(new Uint8Array(await file.slice(offset, offset + 1024 * 1024).arrayBuffer()));
      progress?.(Math.min(100, Math.round((offset + 1024 * 1024) / file.size * 100)));
      await new Promise(resolve => setTimeout(resolve, 0));
    }
    signal?.throwIfAborted(); return bytesToHex(hash.digest());
  } finally { hash.destroy(); }
}
export function textPreview(content: ArrayBuffer) {
  if (content.byteLength > MAX_PREVIEW_BYTES) throw new Error('文件超过 2 MiB，请下载查看');
  const value = new TextDecoder('utf-8', { fatal: true }).decode(content);
  if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/u.test(value)) throw new Error('二进制文件请下载查看');
  return value;
}
/** Raw browser File stays outside Query/Pinia/storage. The upload id is the frozen content identity. */
export function transferFile(id: string, file: File, space: string, csrf: string, signal: AbortSignal, progress: (value: number) => void) {
  return new Promise<components['schemas']['FileUpload']>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const abort = () => xhr.abort();
    xhr.open('PUT', `/api/v1/uploads/${encodeURIComponent(id)}/content`);
    xhr.withCredentials = true; xhr.timeout = 125000;
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
    xhr.setRequestHeader('X-Space-Id', space); xhr.setRequestHeader('X-CSRF-Token', csrf);
    xhr.upload.onprogress = event => { if (event.lengthComputable) progress(Math.round(event.loaded / event.total * 100)); };
    xhr.onload = () => {
      let body: unknown;
      try { body = JSON.parse(xhr.responseText); } catch { reject(new Error('上传响应尚未确认，请读取原上传状态')); return; }
      if (xhr.status === 401) globalThis.dispatchEvent(new Event('platform:session-expired'));
      if (xhr.status < 200 || xhr.status >= 300) {
        const problem = body as Partial<components['schemas']['Problem']>;
        reject(new ApiError({ type: problem.type ?? 'urn:agentplatform:error:file-transfer', title: '文件传输未完成',
          status: xhr.status, code: problem.code ?? 'FILE_TRANSFER_UNCONFIRMED', traceId: problem.traceId ?? '', retryable: false }));
      } else resolve(body as components['schemas']['FileUpload']);
    };
    xhr.onerror = xhr.ontimeout = () => reject(new Error('连接中断，请读取原上传状态，不要重复提交'));
    xhr.onabort = () => reject(new DOMException('传输已中止', 'AbortError'));
    xhr.onloadend = () => signal.removeEventListener('abort', abort);
    if (signal.aborted) { reject(new DOMException('传输已中止', 'AbortError')); return; }
    signal.addEventListener('abort', abort, { once: true }); xhr.send(file);
  });
}
