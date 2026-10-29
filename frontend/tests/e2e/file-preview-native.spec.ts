import { test, expect } from '@playwright/test';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

test.use({ trace: 'off', screenshot: 'off' });
test.skip(process.env.R53_NATIVE !== '1', 'Explicit independent R53 real-dependency fixture only');
test('actual immutable Office/PDF/image/text previews and original download', async ({ browser }) => {
  test.setTimeout(240000);
  const root = resolve(process.cwd(), '../.cache/r53-local');
  const ids: { workspaceId: string; files: Array<{ id: string; versionId: string; name: string; expectedFormat: string; expectedText?: string; sha256?: string }> }
    = JSON.parse(readFileSync(process.env.R53_PREVIEW_IDENTITIES ?? resolve(root, 'native-preview-identities.json'), 'utf8'));
  const context = await browser.newContext({ baseURL: process.env.BASE_URL, viewport: { width: 1440, height: 900 } });
  const page = await context.newPage(); const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  const results: Array<Record<string, unknown>> = [];
  try {
    await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill(process.env.R53_USERNAME ?? 'r53_admin');
    await page.getByLabel('密码', { exact: true }).fill(readFileSync(process.env.R53_PASSWORD_FILE ?? resolve(root, 'native/accounts/r53_admin.password'), 'utf8').trim());
    await page.getByLabel('密码', { exact: true }).press('Enter'); await expect(page).not.toHaveURL(/\/login$/);
    await page.goto(`/app/workspaces/${ids.workspaceId}`);
    for (let index = 0; index < ids.files.length; index++) {
      const file = ids.files[index]; await page.setViewportSize({ width: [375, 768, 1440][Math.min(index, 2)], height: 900 });
      const row = page.locator('.file-list > li').filter({ has: page.locator('strong').filter({ hasText: file.name }) });
      await row.getByRole('button', { name: '只读预览', exact: true }).click();
      const pane = page.getByRole('region', { name: '固定版本文件预览' }); await expect(pane).toContainText(file.versionId);
      if (file.expectedFormat === 'PDF') {
        await expect(pane.getByRole('region', { name: 'PDF只读预览' })).toBeVisible({ timeout: 125000 });
        await expect(pane.locator('.textLayer')).toContainText(file.expectedText ?? /\S+/, { timeout: 30000 });
      } else if (file.expectedFormat === 'IMAGE') {
        const image = pane.getByRole('img'); await expect(image).toBeVisible({ timeout: 125000 });
        await expect.poll(() => image.evaluate(element => (element as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
      } else await expect(pane.getByLabel('文件只读内容')).toContainText(file.expectedText ?? /\S+/, { timeout: 125000 });
      await expect(pane.getByRole('link', { name: '下载原文件', exact: true })).toHaveAttribute('href', new RegExp(`versionId=${file.versionId}`));
      if (index === 0) {
        await page.evaluate(() => { document.documentElement.dataset.theme = 'dark'; document.documentElement.style.zoom = '2'; });
        await page.keyboard.press('Tab'); await expect(pane).toBeVisible();
        await page.evaluate(() => { document.documentElement.dataset.theme = 'light'; document.documentElement.style.zoom = ''; });
      }
      await pane.getByRole('button', { name: '关闭预览', exact: true }).click();
      const download = await context.request.get(`/api/v1/files/${encodeURIComponent(file.id)}/content?versionId=${encodeURIComponent(file.versionId)}`);
      expect(download.ok()).toBe(true); const hash = createHash('sha256').update(await download.body()).digest('hex');
      if (file.sha256) expect(hash).toBe(file.sha256);
      expect(download.headers()['content-disposition']).toContain('attachment');
      results.push({ fileId: file.id, versionId: file.versionId, format: file.expectedFormat, originalDownloadSha256: hash, status: 'PASS' });
    }
    // History uses the same viewer and exact version; it must not become the editor's current content.
    const first = ids.files[0];
    if (first) {
      const row = page.locator('.file-list > li').filter({ has: page.locator('strong').filter({ hasText: first.name }) });
      await row.getByRole('button', { name: '历史版本', exact: true }).click();
      await page.getByRole('button', { name: '只读查看', exact: true }).first().click();
      await expect(page.getByRole('region', { name: '固定版本文件预览' })).toContainText('历史版本');
      if (first.expectedFormat === 'PDF') await expect(page.locator('.textLayer')).toContainText(first.expectedText ?? /\S+/);
      await page.getByRole('button', { name: '关闭预览', exact: true }).click();
      await page.getByRole('button', { name: '关闭面板', exact: true }).click();
    }
    expect(errors).toEqual([]);
    writeFileSync(resolve(root, 'native-preview-browser-result.json'), JSON.stringify({ status: 'PASS', supplierCalls: 0,
      scope: 'Actual API immutable version preview/current+history/local PDF worker+text layer/original download/375,768,1440/200%/keyboard/themes', files: results }, null, 2));
  } finally { await context.close(); }
});
