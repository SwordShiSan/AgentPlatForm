import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);
const yaml = require(path.join(root, 'frontend/node_modules/.pnpm/yaml@2.9.1/node_modules/yaml'));
const workflow = yaml.parse(fs.readFileSync(path.join(root, '.github/workflows/r0-toolchain.yml'), 'utf8'));
const bash = process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash';
for (const job of ['audit-candidate', 'runtime-candidate']) {
  const step = workflow.jobs[job].steps.find(item => item.name?.startsWith('Build and test frozen'));
  assert.ok(step?.run, `Missing candidate build step: ${job}`);
  const directory = fs.mkdtempSync(path.join(root, '.cache/ci-exit-test-'));
  const script = `
docker() { if [ "$1" = build ]; then return 7; fi; return 0; }
python3() { return 7; }
sha256sum() { echo UNEXPECTED_ACCEPTANCE_RECORD; return 0; }
git() { echo UNEXPECTED_ACCEPTANCE_RECORD; return 0; }
${step.run.replaceAll('${{ matrix.tool }}', 'syft')}
`;
  try {
    const result = spawnSync(bash, ['-e', '-c', script], { cwd: directory, encoding: 'utf8' });
    if (result.error) throw result.error;
    assert.equal(result.status, 7, `${job} hid the failing command: ${result.stderr}`);
    assert.ok(!result.stdout.includes('UNEXPECTED_ACCEPTANCE_RECORD'), `${job} wrote acceptance records after failure`);
  } finally {
    assert.equal(path.dirname(path.resolve(directory)), path.join(root, '.cache'));
    fs.rmSync(directory, { recursive: true, force: true });
  }
}
console.log('Actual CI candidate command failures stop before acceptance records: PASS (2 scenarios)');
