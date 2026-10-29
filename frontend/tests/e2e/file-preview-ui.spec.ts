import { test, expect } from '@playwright/test';

// Browser contract fixture: backend conversion, persistence and isolation have separate real-dependency gates.
const id = '0199e000-0000-7000-8000-000000000053';
function pdfFixture() {
  const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [4 0 R 6 0 R] /Count 2 >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'];
  for (let page = 1; page <= 2; page++) {
    const stream = `BT /F1 16 Tf 60 740 Td (R53 page ${page}) Tj ET\n`;
    objects.push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents ${page === 1 ? 5 : 7} 0 R >>`);
    objects.push(`<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}endstream`);
  }
  let value = '%PDF-1.4\n'; const offsets = [0];
  objects.forEach((object, index) => { offsets.push(Buffer.byteLength(value)); value += `${index + 1} 0 obj\n${object}\nendobj\n`; });
  const start = Buffer.byteLength(value); value += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  value += offsets.slice(1).map(offset => `${String(offset).padStart(10, '0')} 00000 n \n`).join('');
  value += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${start}\n%%EOF\n`;
  return Buffer.from(value);
}
for (const width of [375, 768, 1440]) test(`fixed-version PDF and opaque HTML preview at ${width}`, async ({ page }) => {
  await page.setViewportSize({ width, height: 900 }); const pdf = pdfFixture();
  let requested = 'PDF'; let active = 0; let peak = 0; const ranges: string[] = []; const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  const file = (name: string) => ({ id: name.endsWith('.html') ? '0199e000-0000-7000-8000-000000000054' : id, workspaceId: id, path: name, parent: '', name, kind: 'FILE', versionId: name.endsWith('.html') ? '0199e000-0000-7000-8000-000000000054' : id,
    revision: 1, collectionState: 'CONFIRMED', bytes: pdf.length, sha256: 'a'.repeat(64) });
  let currentFile = file('sample.pdf');
  const metadata = () => ({ id: currentFile.id, fileId: currentFile.id, versionId: currentFile.versionId, state: 'READY', format: requested,
    mediaType: requested === 'PDF' ? 'application/pdf' : 'text/plain', bytes: requested === 'PDF' ? pdf.length : Buffer.byteLength(requested === 'TEXT' ? '<h1>HTML source</h1>' : '<h1>Static content</h1><script>window.parent.__previewRan=true</script>'),
    sha256: 'b'.repeat(64), pages: requested === 'PDF' ? 2 : null, revision: requested === 'HTML_STATIC' ? 2 : 1,
    errorCode: null, retryable: false, allowedActions: ['VIEW', 'DOWNLOAD_SOURCE'] });
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.includes('/previews/') && path.endsWith('/content')) {
      if (requested !== 'PDF') return route.fulfill({ body: requested === 'TEXT' ? '<h1>HTML source</h1>' : '<h1>Static content</h1><script>window.parent.__previewRan=true</script>', contentType: 'text/plain' });
      const range = route.request().headers().range;
      expect(range).toBeTruthy(); ranges.push(range); active++; peak = Math.max(peak, active);
      const [, begin, last] = /bytes=(\d+)-(\d+)/.exec(range)!;
      const data = pdf.subarray(Number(begin), Number(last) + 1);
      await new Promise(resolve => setTimeout(resolve, 10)); active--;
      return route.fulfill({ status: 206, body: data, contentType: 'application/pdf', headers: {
        'Content-Range': `bytes ${begin}-${last}/${pdf.length}`, 'Content-Length': String(data.length), 'Accept-Ranges': 'bytes' } });
    }
    let body: unknown;
    if (path.endsWith('/auth/csrf')) body = { csrfToken: 'a'.repeat(43) };
    else if (path.endsWith('/previews') && route.request().method() === 'POST') {
      requested = route.request().postDataJSON().format === 'HTML_STATIC' ? 'HTML_STATIC' : currentFile.name.endsWith('.html') ? 'TEXT' : 'PDF'; body = metadata();
    } else if (path.includes('/previews/')) body = metadata();
    else if (path.endsWith('/me')) body = { id, username: 'r53_admin', spaces: [{ id, type: 'PERSONAL' }] };
    else if (path.endsWith('/storage-usage')) body = { committedBytes: 5, reservedBytes: 0, objectLimitBytes: 10737418240, workspaceQuotaBytes: 2147483648, workspaceQuotaInodes: 100000, measuredBytes: null };
    else if (path.endsWith('/files')) body = { items: [currentFile], nextCursor: null };
    else body = { id, name: 'R53 fixture', state: 'READY', quotaInodes: 100000 };
    return route.fulfill({ json: body });
  });
  await page.goto(`/app/workspaces/${id}`); await page.getByRole('button', { name: '只读预览', exact: true }).click();
  await expect(page.getByRole('region', { name: 'PDF只读预览' })).toBeVisible();
  await expect(page.locator('.textLayer')).toContainText('R53 page 1');
  await page.getByRole('button', { name: '下一页', exact: true }).click(); await expect(page.locator('.textLayer')).toContainText('R53 page 2');
  expect(peak).toBe(1); expect(ranges.length).toBeGreaterThan(0);
  await page.getByRole('button', { name: '关闭预览', exact: true }).click();
  currentFile = file('page.html'); await page.reload(); await page.getByRole('button', { name: '只读预览', exact: true }).click();
  await expect(page.getByLabel('文件只读内容')).toContainText('<h1>HTML source</h1>');
  await page.getByRole('button', { name: '显示静态 HTML 预览', exact: true }).click();
  const frame = page.locator('iframe[title="隔离静态 HTML 预览"]'); await expect(frame).toHaveAttribute('sandbox', '');
  await expect(page.frameLocator('iframe[title="隔离静态 HTML 预览"]').getByRole('heading', { name: 'Static content' })).toBeVisible();
  expect(await page.evaluate(() => (window as unknown as { __previewRan?: boolean }).__previewRan)).toBeUndefined();
  await page.evaluate(() => { document.documentElement.dataset.theme = 'dark'; document.documentElement.style.zoom = '2'; });
  await page.keyboard.press('Tab'); await expect(page.getByRole('link', { name: '下载原文件', exact: true })).toBeVisible();
  expect(await page.evaluate(() => Object.values(sessionStorage).some(value => value.includes('Static content') || value.includes('HTML source')))).toBe(false);
  expect(errors).toEqual([]);
});

test('artifact fixed-version preview preserves source download through failed, blocked and refreshed states', async ({ page }) => {
  const identity = (suffix: string) => `0199e000-0000-7000-8000-${suffix.padStart(12, '0')}`;
  const taskId = identity('55'); const operationId = identity('56'); const artifactId = identity('57');
  const fileId = identity('58'); const versionId = identity('59'); const previewId = identity('60');
  const content = 'Fixed artifact version 666\n';
  let state = 'FAILED'; let creates = 0; let reads = 0; let contentReads = 0;
  let release!: () => void; const pendingCreate = new Promise<void>(done => { release = done; });
  const errors: string[] = []; const unexpected: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  const artifact = { id: artifactId, taskId, operationId, workspaceId: id, fileId, versionId,
    title: 'fixed-artifact.txt', sha256: 'a'.repeat(64), bytes: Buffer.byteLength(content), contentState: 'VERIFIED_CONTENT', testStatus: 'NOT_RUN' };
  const metadata = () => ({ id: previewId, fileId, versionId, state, format: state === 'READY' ? 'TEXT' : 'AUTO',
    mediaType: state === 'READY' ? 'text/plain' : null, bytes: state === 'READY' ? Buffer.byteLength(content) : null,
    sha256: state === 'READY' ? 'a'.repeat(64) : null, pages: null, revision: state === 'FAILED' ? 2 : state === 'BLOCKED' ? 3 : 4,
    errorCode: state === 'FAILED' ? 'PREVIEW_DOCUMENT_INVALID' : state === 'BLOCKED' ? 'PREVIEW_STOP_UNCONFIRMED' : null,
    retryable: false, allowedActions: state === 'READY' ? ['VIEW', 'DOWNLOAD_SOURCE'] : ['QUERY', 'DOWNLOAD_SOURCE'] });
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === `/api/v1/files/${fileId}/previews`) {
      creates++; expect(route.request().postDataJSON()).toEqual({ versionId, format: 'AUTO' });
      await pendingCreate; return route.fulfill({ status: 202, json: metadata() });
    }
    if (path === `/api/v1/previews/${previewId}/content`) {
      contentReads++; expect(state).toBe('READY'); return route.fulfill({ contentType: 'text/plain', body: content });
    }
    if (path === `/api/v1/previews/${previewId}`) { reads++; return route.fulfill({ json: metadata() }); }
    let body: unknown;
    if (path.endsWith('/auth/csrf')) body = { csrfToken: 'a'.repeat(43) };
    else if (path.endsWith('/me')) body = { id, username: 'r53_admin', spaces: [{ id, type: 'PERSONAL' }] };
    else if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: ': fixture\n\n' });
    else if (path.endsWith('/snapshot')) body = { conversationId: id, cursor: 0, messages: [], pending: [],
      tasks: [{ taskId, status: 'SUCCEEDED', version: 1, cancelRequested: false, reasonCode: null }] };
    else if (path.endsWith('/operations')) body = { items: [{ operationId, taskId, toolName: 'workspace_write', status: 'SUCCEEDED',
      phase: 'SUCCEEDED', summary: 'Fixed artifact write', createdAt: '2026-10-29T00:00:00Z', endedAt: '2026-10-29T00:00:01Z',
      errorCode: null, traceId: null, review: null, resultAvailable: true }], nextCursor: null };
    else if (path.endsWith('/result')) body = { operationId, taskId, status: 'SUCCEEDED', summary: 'Sealed immutable output',
      exitCode: null, stdout: null, stderr: null, truncated: false, errorCode: null,
      files: [{ fileId, versionId, path: artifact.title, bytes: artifact.bytes, sha256: artifact.sha256 }] };
    else if (path.endsWith('/artifacts')) body = { items: [artifact], nextCursor: null };
    else if (path.endsWith('/permissions')) body = { taskId, workspaceId: id, mode: 'FULL', version: 1, policyVersion: 'fixture', mutable: false, effectiveRestrictions: [], grants: [] };
    else if (path.endsWith('/configuration')) body = { configSnapshotId: id, configHash: 'b'.repeat(64), bundleId: id,
      values: { modelName: 'fixture', credentialSource: 'PLATFORM' }, restrictions: { credentialActive: true, modelActive: true } };
    else if (path.endsWith('/effective-config')) body = { values: { modelId: id, credentialSource: 'PLATFORM' }, sources: { modelId: 'PLATFORM' } };
    else if (path.endsWith('/preferences')) body = { version: 1, defaultModelId: id, defaultCredentialSource: 'PLATFORM' };
    else if (path.endsWith('/models')) body = { items: [{ id, name: 'fixture' }] };
    else if (path.endsWith('/usage-quota')) body = [];
    else if (path.endsWith('/model-credentials')) body = { version: 1, userConfigured: false, platformAvailable: true };
    else if (path.endsWith('/usage')) body = { items: [] };
    else if (path.endsWith('/reconciliation')) body = { taskId, stopStage: 'STOPPED', stopConfirmed: true, unknownCalls: 0, calls: [], revision: 1 };
    else if (path.endsWith('/recovery')) body = { taskId, stage: 'NONE', eligible: false, revision: 0 };
    else if (path.endsWith('/diagnostics')) body = { taskId, traceId: id, code: null, classification: 'NONE', hasPartialOutput: false, suggestedAction: 'NONE', automaticReplayAllowed: false };
    else if (path.endsWith('/workspaces') || path.endsWith('/conversations')) body = { items: [], nextCursor: null };
    else { unexpected.push(path); return route.fulfill({ status: 404, json: { code: 'FIXTURE_ROUTE_MISSING', status: 404 } }); }
    return route.fulfill({ json: body });
  });
  await page.goto(`/app/chat/${id}`);
  const open = async () => {
    await page.getByRole('button', { name: '查看产物证据', exact: true }).click();
    const evidence = page.getByRole('region', { name: '固定版本产物证据' });
    await expect(evidence).toContainText('功能验证：未运行');
    await expect(evidence.getByRole('link', { name: /下载固定版本/ })).toHaveAttribute('href', `/api/v1/artifacts/${artifactId}/download`);
    await evidence.getByRole('button', { name: '预览固定版本', exact: true }).click();
    return page.getByRole('region', { name: '固定版本文件预览' });
  };
  let pane = await open();
  try { await expect(pane).toContainText('正在读取预览'); } finally { release(); }
  await expect(pane).toContainText('预览未能生成'); await expect(pane).toContainText('PREVIEW_DOCUMENT_INVALID');
  await expect(pane).toContainText(versionId); await expect(pane).toContainText('明确封存的历史版本');
  await expect(pane.getByRole('link', { name: '下载原文件', exact: true })).toHaveAttribute('href', `/api/v1/files/${fileId}/content?versionId=${versionId}`);
  await expect(pane.getByRole('button', { name: '重试明确失败的转换', exact: true })).toHaveCount(0);
  state = 'BLOCKED'; await pane.getByRole('button', { name: '读取原作业状态', exact: true }).click();
  await expect(pane).toContainText('预览结果待核对，不会重复转换'); expect(contentReads).toBe(0);
  await page.reload(); pane = await open();
  await expect(pane).toContainText('预览结果待核对'); expect(creates).toBe(1);
  state = 'READY'; await pane.getByRole('button', { name: '读取原作业状态', exact: true }).click();
  await expect(pane.getByLabel('历史版本只读内容')).toHaveText(content.trim());
  // Metadata observation and an explicit refresh may each safely read the same content;
  // only conversion creation must remain exactly once across page reload.
  expect(creates).toBe(1); expect(reads).toBeGreaterThanOrEqual(3);
  expect(contentReads).toBeGreaterThanOrEqual(1); expect(contentReads).toBeLessThanOrEqual(2);
  expect(unexpected).toEqual([]); expect(errors).toEqual([]);
});
