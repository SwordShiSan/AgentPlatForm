import { afterEach, describe, expect, it, vi } from 'vitest';
import { subscribeReplay, type ReplaySubscription } from './event-stream';

const conversation = '0199e000-0000-7000-8000-000000000003';
const space = '0199e000-0000-7000-8000-000000000002';
const frame = {
  schemaVersion: 1, eventId: '0199e000-0000-7000-8000-000000000004', spaceId: space,
  conversationId: conversation, seq: 1, type: 'message.delta', occurredAt: '2026-10-20T00:00:00Z', entityVersion: 1,
  payload: { messageId: '0199e000-0000-7000-8000-000000000005', blockId: '0199e000-0000-7000-8000-000000000006', offset: 0, text: '😀你好' },
};
const wire = (value = frame) => `id: ${value.seq}\r\nevent: platform_event\r\ndata: ${JSON.stringify(value)}\r\n\r\n`;
const response = (data: string, split = 1) => new Response(new ReadableStream({
  start(controller) {
    const bytes = new TextEncoder().encode(data);
    for (let i = 0; i < bytes.length; i += split) controller.enqueue(bytes.slice(i, i + split));
    controller.close();
  },
}), { headers: { 'Content-Type': 'text/event-stream' } });
const disposals: (() => void)[] = [];
afterEach(() => { disposals.splice(0).forEach(stop => stop()); vi.useRealTimers(); vi.unstubAllGlobals(); });
function fixture(overrides: Partial<ReplaySubscription> = {}) {
  let cursor = 0;
  return { conversation, space, cursor: () => cursor, apply: vi.fn(value => { cursor = value.seq; }), rebase: vi.fn(async () => {}),
    status: vi.fn(), unauthorized: vi.fn(), failed: vi.fn(), ...overrides } satisfies ReplaySubscription;
}

describe('committed replay transport', () => {
  it('renews healthy timed streams without accumulating failure backoff', async () => {
    vi.useFakeTimers(); const options=fixture();
    const fetcher=vi.fn<typeof fetch>().mockImplementation(async ()=> {
      let timer: ReturnType<typeof setTimeout>;
      return new Response(new ReadableStream({
        start(controller) {
          controller.enqueue(new TextEncoder().encode(': heartbeat\n\n'));
          timer=setTimeout(()=>controller.close(),25000);
        },
        cancel(){clearTimeout(timer);},
      }),{headers:{'Content-Type':'text/event-stream'}});
    });
    disposals.push(subscribeReplay(options,fetcher));
    await vi.advanceTimersByTimeAsync(52500);
    expect(fetcher).toHaveBeenCalledTimes(3);
    expect(options.rebase).not.toHaveBeenCalled();
  });
  it('parses byte-split UTF8/CRLF with generated validation and resumes from applied cursor', async () => {
    vi.useFakeTimers();
    const options = fixture();
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(response(wire())).mockResolvedValue(response(': heartbeat\n\n'));
    disposals.push(subscribeReplay(options, fetcher));
    await vi.advanceTimersByTimeAsync(100);
    expect(options.apply).toHaveBeenCalledExactlyOnceWith(frame);
    await vi.advanceTimersByTimeAsync(1300);
    expect(fetcher.mock.calls[1]?.[0]).toContain('after=1');
    expect(options.rebase).not.toHaveBeenCalled();
  });
  it('does not apply malformed or foreign envelopes and stops after one repair', async () => {
    vi.useFakeTimers(); const options = fixture();
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => response(wire({ ...frame, spaceId: conversation })));
    disposals.push(subscribeReplay(options, fetcher));
    await vi.advanceTimersByTimeAsync(3000);
    expect(options.apply).not.toHaveBeenCalled();
    expect(options.rebase).toHaveBeenCalledTimes(1);
    expect(options.failed).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it.each([409, 410])('repairs HTTP %s once without replaying a task', async status => {
    vi.useFakeTimers(); const options = fixture();
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => new Response('', { status }));
    disposals.push(subscribeReplay(options, fetcher));
    await vi.advanceTimersByTimeAsync(3000);
    expect(options.rebase).toHaveBeenCalledTimes(1); expect(options.failed).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls.every(([, init]) => init?.method === undefined)).toBe(true);
  });
  it.each([401, 403, 404])('stops HTTP %s rather than reconnecting forever', async status => {
    vi.useFakeTimers(); const options = fixture();
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response('', { status }));
    disposals.push(subscribeReplay(options, fetcher)); await vi.advanceTimersByTimeAsync(60000);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(status === 401 ? options.unauthorized : options.failed).toHaveBeenCalledTimes(1);
  });
  it('bounds malformed partial input and aborts late responses after disposal', async () => {
    vi.useFakeTimers(); const options = fixture();
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => response('data: ' + 'x'.repeat(262145), 16384));
    const stop = subscribeReplay(options, fetcher); disposals.push(stop);
    await vi.advanceTimersByTimeAsync(100);
    expect(options.rebase).toHaveBeenCalledTimes(1); expect(options.apply).not.toHaveBeenCalled();
    stop(); await vi.advanceTimersByTimeAsync(60000); expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it('pauses while offline and makes one immediate attempt when online', async () => {
    vi.useFakeTimers(); const options=fixture(); const network={onLine:true}; const events=new EventTarget();
    vi.stubGlobal('navigator',network);
    vi.stubGlobal('addEventListener',events.addEventListener.bind(events));
    vi.stubGlobal('removeEventListener',events.removeEventListener.bind(events));
    const fetcher=vi.fn<typeof fetch>().mockImplementation(async (_,init)=>new Promise((_,reject)=>{
      init?.signal?.addEventListener('abort',()=>reject(new DOMException('Aborted','AbortError')),{once:true});
    }));
    disposals.push(subscribeReplay(options,fetcher));
    network.onLine=false; events.dispatchEvent(new Event('offline'));
    await vi.advanceTimersByTimeAsync(60000); expect(fetcher).toHaveBeenCalledTimes(1);
    network.onLine=true; events.dispatchEvent(new Event('online')); events.dispatchEvent(new Event('online'));
    await vi.advanceTimersByTimeAsync(100); expect(fetcher).toHaveBeenCalledTimes(2);
    expect(options.rebase).not.toHaveBeenCalled();
  });
  it('ignores a response arriving after its generation was disposed', async () => {
    vi.useFakeTimers(); const options=fixture();
    let resolve!: (value:Response)=>void;
    const fetcher=vi.fn<typeof fetch>().mockImplementation(()=>new Promise(done=>{resolve=done;}));
    const stop=subscribeReplay(options,fetcher); disposals.push(stop);
    stop(); resolve(response(wire())); await vi.advanceTimersByTimeAsync(60000);
    expect(options.apply).not.toHaveBeenCalled();expect(options.rebase).not.toHaveBeenCalled();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it('repairs a truncated UTF8 codepoint once and stops a repeated malformed stream', async () => {
    vi.useFakeTimers(); const options=fixture();
    const fetcher=vi.fn<typeof fetch>().mockImplementation(async ()=>new Response(Uint8Array.of(0xf0),{headers:{'Content-Type':'text/event-stream'}}));
    disposals.push(subscribeReplay(options,fetcher));
    await vi.advanceTimersByTimeAsync(3000);
    expect(options.rebase).toHaveBeenCalledTimes(1); expect(options.failed).toHaveBeenCalledTimes(1);
    expect(options.apply).not.toHaveBeenCalled(); expect(fetcher).toHaveBeenCalledTimes(2);
  });
});
