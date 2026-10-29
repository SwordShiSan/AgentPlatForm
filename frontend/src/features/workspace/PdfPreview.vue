<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { getDocument, GlobalWorkerOptions, PDFDataRangeTransport, TextLayer, type PDFDocumentLoadingTask,
  type PDFDocumentProxy, type RenderTask } from 'pdfjs-dist';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import 'pdfjs-dist/web/pdf_viewer.css';
import { PDF_RANGE_BYTES, SerialPreviewRanges } from './preview-content';

const props = defineProps<{ url: string; spaceId: string; bytes: number }>();
GlobalWorkerOptions.workerSrc = workerUrl;
const container = ref<HTMLElement>(); const stage = ref<HTMLElement>(); const canvas = ref<HTMLCanvasElement>(); const text = ref<HTMLElement>();
const page = ref(1); const pages = ref(0); const zoom = ref(100); const loading = ref(true); const error = ref(''); const width = ref(0);
let loadingTask: PDFDocumentLoadingTask | undefined; let document: PDFDocumentProxy | undefined;
let renderTask: RenderTask | undefined; let textLayer: TextLayer | undefined; let observer: ResizeObserver | undefined;
let controller = new AbortController(); let renderVersion = 0; let disposed = false;

class RangeTransport extends PDFDataRangeTransport {
  constructor(private readonly ranges: SerialPreviewRanges) { super(props.bytes, null, true); }
  override requestDataRange(begin: number, end: number) {
    void this.ranges.request(begin, end, (offset, chunk) => this.onDataRange(offset, chunk)).catch(failure => {
      if (!controller.signal.aborted) { error.value = failure instanceof Error ? failure.message : 'PDF读取失败'; loading.value = false; controller.abort(); void loadingTask?.destroy(); }
    });
  }
  override abort() { controller.abort(); }
}
async function render() {
  if (!document || !canvas.value || !stage.value || !text.value || disposed) return;
  const version = ++renderVersion; renderTask?.cancel(); textLayer?.cancel(); loading.value = true;
  try {
    const pdfPage = await document.getPage(page.value);
    if (version !== renderVersion || disposed) return;
    const natural = pdfPage.getViewport({ scale: 1 });
    const scale = Math.min((Math.max(160, width.value - 24) / natural.width) * zoom.value / 100, 4);
    const viewport = pdfPage.getViewport({ scale });
    const pixelRatio = Math.min(globalThis.devicePixelRatio || 1, 2, Math.sqrt(16_000_000 / (viewport.width * viewport.height)));
    const target = canvas.value;
    target.width = Math.ceil(viewport.width * pixelRatio); target.height = Math.ceil(viewport.height * pixelRatio);
    target.style.width = `${viewport.width}px`; target.style.height = `${viewport.height}px`;
    stage.value.style.width = `${viewport.width}px`; stage.value.style.height = `${viewport.height}px`;
    stage.value.style.setProperty('--scale-factor', String(viewport.scale));
    stage.value.style.setProperty('--user-unit', String(viewport.userUnit));
    text.value.replaceChildren();
    renderTask = pdfPage.render({ canvas: target, viewport,
      ...(pixelRatio === 1 ? {} : { transform: [pixelRatio, 0, 0, pixelRatio, 0, 0] }) });
    await renderTask.promise;
    if (version !== renderVersion || disposed) return;
    textLayer = new TextLayer({ textContentSource: pdfPage.streamTextContent(), container: text.value, viewport });
    await textLayer.render(); pdfPage.cleanup();
  } catch (failure) {
    if (!disposed && version === renderVersion && (failure as Error).name !== 'RenderingCancelledException') error.value = 'PDF页面读取失败，可重新打开预览或下载原文件。';
  } finally { if (version === renderVersion && !disposed) loading.value = false; }
}
async function load() {
  const ranges = new SerialPreviewRanges(props.url, props.spaceId, props.bytes, controller.signal);
  loadingTask = getDocument({ range: new RangeTransport(ranges), rangeChunkSize: PDF_RANGE_BYTES,
    disableAutoFetch: true, disableStream: true, enableXfa: false, stopAtErrors: true,
    maxImageSize: 40_000_000, canvasMaxAreaInBytes: 64 * 1024 * 1024,
    cMapUrl: '/pdfjs/cmaps/', cMapPacked: true, standardFontDataUrl: '/pdfjs/standard_fonts/',
    wasmUrl: '/pdfjs/wasm/', iccUrl: '/pdfjs/iccs/' });
  loadingTask.onPassword = () => { error.value = '加密PDF仅支持下载原文件。'; controller.abort(); void loadingTask?.destroy(); };
  try {
    document = await loadingTask.promise;
    if (disposed) { await loadingTask.destroy(); return; }
    if (document.numPages > 200) { error.value = 'PDF超过200页预览上限，请下载原文件。'; await loadingTask.destroy(); document = undefined; return; }
    pages.value = document.numPages; await nextTick(); await render();
  } catch { if (!disposed && !error.value) error.value = 'PDF无法读取，请下载原文件。'; }
  finally { if (!disposed) loading.value = false; }
}
watch([page, zoom, width], () => { void render(); });
onMounted(() => {
  observer = new ResizeObserver(entries => { width.value = Math.round(entries[0]?.contentRect.width ?? 0); });
  if (container.value) { width.value = container.value.clientWidth; observer.observe(container.value); }
  void load();
});
onBeforeUnmount(() => {
  disposed = true; renderVersion++; controller.abort(); observer?.disconnect(); renderTask?.cancel(); textLayer?.cancel();
  void loadingTask?.destroy(); if (canvas.value) { canvas.value.width = 0; canvas.value.height = 0; } text.value?.replaceChildren();
});
</script>
<template>
  <section ref="container" class="pdf-preview" aria-label="PDF只读预览" :aria-busy="loading">
    <nav aria-label="PDF翻页与缩放">
      <button :disabled="page <= 1 || !pages" @click="page--">上一页</button>
      <label>页码<input v-model.number="page" type="number" min="1" :max="pages || 1" @change="page = Math.min(pages || 1, Math.max(1, page || 1))"></label>
      <span>／{{ pages }}</span><button :disabled="page >= pages || !pages" @click="page++">下一页</button>
      <label>缩放<select v-model.number="zoom"><option :value="50">50%</option><option :value="75">75%</option><option :value="100">适合宽度</option><option :value="150">150%</option><option :value="200">200%</option></select></label>
    </nav>
    <p v-if="loading" role="status">正在读取PDF页面…</p><p v-if="error" role="alert">{{ error }}</p>
    <div class="pdf-scroll" tabindex="0" aria-label="PDF页面">
      <div ref="stage" class="pdf-stage"><canvas ref="canvas" aria-hidden="true" /><div ref="text" class="textLayer" /></div>
    </div>
    <p>只读展示页面和文字，不运行文档脚本、表单或外部链接。</p>
  </section>
</template>
<style scoped>
.pdf-preview{min-width:0}nav{display:flex;flex-wrap:wrap;gap:.65rem;align-items:center;margin-block:.75rem}label{display:flex;align-items:center;gap:.4rem}button,input,select{font:inherit;min-height:44px;box-sizing:border-box;color:inherit;background:transparent;border:1px solid currentColor;border-radius:.35rem;padding:.35rem .55rem}input{max-width:5rem}.pdf-scroll{max-height:75vh;overflow:auto;background:#e3e5e8;padding:.75rem}.pdf-stage{position:relative;margin:auto;background:white;color:black}canvas{display:block}.textLayer{position:absolute;inset:0;overflow:clip;line-height:1;text-align:initial}p{overflow-wrap:anywhere}
</style>
