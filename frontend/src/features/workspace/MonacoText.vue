<script setup lang="ts">
import { onMounted, onBeforeUnmount, watch, ref } from 'vue';
import * as monaco from 'monaco-editor/editor/editor.api.js';
import Worker from 'monaco-editor/editor/editor.worker.js?worker';
const props = defineProps<{ modelValue: string; original?: string; dark?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [string] }>();
const host = ref<HTMLElement>();
let editor: monaco.editor.IStandaloneCodeEditor | monaco.editor.IStandaloneDiffEditor;
let model: monaco.editor.ITextModel; let base: monaco.editor.ITextModel | undefined;
let listener: monaco.IDisposable;
const workers: globalThis.Worker[] = [];
onMounted(() => {
  (globalThis as typeof globalThis & { MonacoEnvironment: { getWorker: () => globalThis.Worker } }).MonacoEnvironment = { getWorker: () => { const worker = new Worker(); workers.push(worker); return worker; } };
  model = monaco.editor.createModel(props.modelValue, 'plaintext');
  const options = { automaticLayout: true, minimap: { enabled: false }, theme: props.dark ? 'vs-dark' : 'vs', fontSize: 14 };
  if (props.original !== undefined) {
    base = monaco.editor.createModel(props.original, 'plaintext');
    const diff = monaco.editor.createDiffEditor(host.value!, options); diff.setModel({ original: base, modified: model }); editor = diff;
  } else { const single = monaco.editor.create(host.value!, options); single.setModel(model); editor = single; }
  listener = model.onDidChangeContent(() => emit('update:modelValue', model.getValue()));
});
watch(() => props.modelValue, value => { if (model && value !== model.getValue()) model.setValue(value); });
watch(() => props.original, value => { if (base && value !== undefined && value !== base.getValue()) base.setValue(value); });
watch(() => props.dark, dark => monaco.editor.setTheme(dark ? 'vs-dark' : 'vs'));
onBeforeUnmount(() => { listener?.dispose(); editor?.dispose(); model?.dispose(); base?.dispose(); workers.forEach(w => w.terminate()); });
</script>
<template><div ref="host" class="monaco-host" aria-label="文本编辑器" /></template>
<style scoped>.monaco-host{height:55vh;min-height:260px;width:100%;min-width:0}</style>
