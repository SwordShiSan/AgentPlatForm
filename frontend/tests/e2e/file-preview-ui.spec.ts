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
