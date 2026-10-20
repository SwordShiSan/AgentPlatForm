import { createParser } from 'eventsource-parser';
import validateEvent from '../../shared/api/generated/validate-event.js';
import type { PlatformEvent } from '../../shared/api/generated/events';
import { SnapshotRequired } from './stream-state';

export type StreamStatus = 'connecting' | 'live' | 'reconnecting' | 'offline' | 'stopped';
export interface ReplaySubscription {
  conversation: string;
  space: string;
  cursor: () => number;
  apply: (event: PlatformEvent) => void;
  rebase: (signal: AbortSignal) => Promise<void>;
  status: (status: StreamStatus) => void;
  unauthorized: () => void;
  failed: () => void;
}

async function problemCode(response: Response): Promise<string | undefined> {
  if (!response.headers.get('Content-Type')?.includes('application/problem+json') || !response.body) return;
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let text = ''; let bytes = 0;
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      bytes += chunk.value.byteLength;
      if (bytes > 16384) return;
      text += decoder.decode(chunk.value, { stream: true });
    }
    const value = JSON.parse(text + decoder.decode()) as { code?: unknown };
    return typeof value.code === 'string' ? value.code : undefined;
  } catch { return; }
  finally { try { await reader.cancel(); } catch { /* Lost connection. */ } reader.releaseLock(); }
}

/** One subscription owns only replay, never task execution. Applied cache state owns its cursor. */
export function subscribeReplay(options: ReplaySubscription, transport: typeof fetch = globalThis.fetch): () => void {
  let stopped = false;
  let controller: AbortController | undefined;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let failures = 0;
  let rebased = false;
  let reading = false;
  const connected = () => globalThis.navigator?.onLine !== false;
  const state = (value: StreamStatus) => { if (!stopped) options.status(value); };
  const stop = () => {
    if (stopped) return;
    stopped = true; controller?.abort(); if (timer) clearTimeout(timer);
    globalThis.removeEventListener?.('offline', offline);
    globalThis.removeEventListener?.('online', online);
  };
  const retry = () => {
    if (stopped) return;
    if (!connected()) { state('offline'); return; }
    state('reconnecting');
    const delay = [1000, 2000, 4000, 8000, 15000][Math.min(failures++, 4)]!;
    timer = setTimeout(() => { timer = undefined; void read(); }, delay + Math.random() * delay * 0.2);
  };
  const fail = () => { if (!stopped) { state('stopped'); options.failed(); stop(); } };
  const repair = async (signal: AbortSignal) => {
    if (rebased) { fail(); return; }
    rebased = true;
    const before = options.cursor();
    await options.rebase(signal);
    // A new canonical task can legitimately require a snapshot. Permit the next
    // refresh only after authoritative progress; identical bad frames still stop.
    if (options.cursor() > before) rebased = false;
    if (!stopped && !signal.aborted) retry();
  };
  async function read() {
    if (stopped || reading) return;
    if (!connected()) { state('offline'); return; }
    reading = true; controller = new AbortController();
    const signal = controller.signal;
    const deadline = setTimeout(() => controller?.abort(), 35000);
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    try {
      state(failures ? 'reconnecting' : 'connecting');
      const response = await transport(`/api/v1/conversations/${encodeURIComponent(options.conversation)}/events?after=${options.cursor()}`, {
        credentials: 'same-origin', signal, headers: { Accept: 'text/event-stream', 'X-Space-Id': options.space },
      });
      if (stopped || signal.aborted) return;
      if (response.status === 401) { options.unauthorized(); stop(); return; }
      if ([403, 404].includes(response.status)) { fail(); return; }
      if ([409, 410].includes(response.status)) { await repair(signal); return; }
      if (response.status === 503) {
        if (await problemCode(response) === 'EVENT_STREAM_OVERSIZED') { await repair(signal); return; }
        retry(); return;
      }
      if (!response.ok || !response.headers.get('Content-Type')?.startsWith('text/event-stream') || !response.body) { fail(); return; }
      state('live');
      const connectedAt = Date.now();
      let parseFailure: Error | undefined;
      let bufferedBytes = 0;
      const parser = createParser({
        maxBufferSize: 262144,
        onError: () => { parseFailure = new SnapshotRequired('Invalid SSE frame'); },
        onComment: () => { bufferedBytes = 0; },
        onEvent: frame => {
          if (stopped || signal.aborted || parseFailure) return;
          try {
            if (frame.event !== 'platform_event') throw new SnapshotRequired('Unsupported SSE event');
            const value: unknown = JSON.parse(frame.data);
            if (!validateEvent(value) || value.conversationId !== options.conversation || value.spaceId !== options.space
                || frame.id !== String(value.seq)) throw new SnapshotRequired('Invalid event envelope');
            const before = options.cursor();
            options.apply(value);
            if (options.cursor() > before) rebased = false;
            bufferedBytes = 0;
            failures = 0;
          } catch { parseFailure = new SnapshotRequired('Event projection requires a snapshot'); }
        },
      });
      const decoder = new TextDecoder('utf-8', { fatal: true });
      reader = response.body.getReader();
      while (!stopped && !signal.aborted) {
        const item = await reader.read();
        if (item.done) {
          let tail: string;
          try { tail = decoder.decode(); } catch { throw new SnapshotRequired('Invalid UTF8 tail'); }
          if (tail) parser.feed(tail);
          // A disconnect may split a frame. Only dispatched events advanced the
          // cursor, so discard the tail and replay it from the committed cursor.
          break;
        }
        // Bound each feed and pending input; parser independently bounds partial frame characters.
        for (let offset = 0; offset < item.value.byteLength; offset += 16384) {
          const bytes = item.value.subarray(offset, offset + 16384);
          bufferedBytes += bytes.byteLength;
          if (bufferedBytes > 1048576) throw new SnapshotRequired('SSE byte buffer exceeded');
          let decoded: string;
          try { decoded = decoder.decode(bytes, { stream: true }); }
          catch { throw new SnapshotRequired('Invalid UTF8'); }
          parser.feed(decoded);
          if (parseFailure) throw parseFailure;
        }
      }
      if (!stopped && !signal.aborted) {
        if (Date.now() - connectedAt >= 20000) failures = 0;
        retry();
      }
    } catch (error) {
      if (!stopped && !signal.aborted) {
        if (error instanceof SnapshotRequired) {
          try { await reader?.cancel(); await repair(signal); } catch { fail(); }
        } else retry();
      }
    } finally {
      clearTimeout(deadline);
      try { await reader?.cancel(); } catch { /* Disconnected network. */ }
      reader?.releaseLock(); reading = false;
      // An online notification can arrive while the previous aborted read is unwinding.
      if (!stopped && !timer && connected() && signal.aborted) retry();
    }
  }
  function offline() { if (timer) clearTimeout(timer); timer = undefined; state('offline'); controller?.abort(); }
  function online() { if (timer) clearTimeout(timer); timer = undefined; if (!reading) void read(); }
  globalThis.addEventListener?.('offline', offline);
  globalThis.addEventListener?.('online', online);
  void read();
  return stop;
}
