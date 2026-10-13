import { readdir, readFile, access } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const skip = new Set(['.git', '.tools', '.cache', 'node_modules', 'dist', 'target', 'evidence', 'test-results', 'playwright-report']);
let checked = 0;
let external = 0;
const failures = [];
async function walk(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (skip.has(entry.name)) continue;
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) await walk(path);
    else if (entry.name.endsWith('.md')) {
      const source = await readFile(path, 'utf8');
      for (const match of source.matchAll(/\[[^\]]*\]\(([^)]+)\)/g)) {
        const target = match[1].replace(/^<|>$/g, '').split('#')[0];
        if (!target || /^[a-z][a-z\d+.-]*:/i.test(target)) continue;
        const destination = resolve(dirname(path), decodeURIComponent(target));
        if (!destination.startsWith(root + '/') && !destination.startsWith(root + '\\')) {
          external++;
          if (process.env.REQUIRE_CANONICAL_DOCS !== '1') continue;
        }
        try { await access(destination); checked++; }
        catch { failures.push(`${path}: ${target}`); }
      }
    }
  }
}
await walk(root);
if (failures.length) throw new Error(`Broken links:\n${failures.join('\n')}`);
console.log(`Links passed: ${checked}; canonical external references: ${external} (${process.env.REQUIRE_CANONICAL_DOCS === '1' ? 'verified' : 'not shipped with repository; local handoff check required'}).`);
