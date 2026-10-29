import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { cp, mkdir, stat } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, sep } from 'node:path';

const pdfAssets = resolve(fileURLToPath(new URL('./node_modules/pdfjs-dist/', import.meta.url)));
const previewAssets = ['cmaps', 'standard_fonts', 'wasm', 'iccs'];

export default defineConfig({
  plugins: [vue(), {
    name: 'local-pdf-preview-assets',
    async closeBundle() {
      await mkdir('dist/pdfjs', { recursive: true });
      for (const folder of previewAssets) {
        const source = resolve(pdfAssets, folder);
        if (await stat(source).catch(() => undefined)) await cp(source, `dist/pdfjs/${folder}`, { recursive: true });
      }
    },
    configureServer(server) {
      // Serve only the four dependency asset directories, never arbitrary node_modules paths.
      server.middlewares.use('/pdfjs', async (req, res, next) => {
        const relative = req.url?.split('?')[0];
        if (!relative || !previewAssets.some(folder => relative.startsWith(`/${folder}/`))) { next(); return; }
        const file = resolve(pdfAssets, '.' + relative);
        if (!file.startsWith(pdfAssets + sep) || !await stat(file).then(value => value.isFile()).catch(() => false)) { res.statusCode = 404; res.end(); return; }
        if (file.endsWith('.wasm')) res.setHeader('Content-Type', 'application/wasm');
        else res.setHeader('Content-Type', 'application/octet-stream');
        const { createReadStream } = await import('node:fs'); createReadStream(file).pipe(res);
      });
    },
  }],
  server: {
    proxy: { '/api': 'http://127.0.0.1:8080' },
  },
});
