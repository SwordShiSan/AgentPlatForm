import { test, expect } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
const root = resolve(process.cwd(), '../.cache/r52-local');
test.use({ trace: 'off', screenshot: 'off' });
test.skip(process.env.R52_NATIVE !== '1', 'Explicit isolated R52 native fixture only');
test('actual lifecycle UI preserves identities, immutable history and original requests', async ({ browser }) => {
  test.setTimeout(180000);
  const context = await browser.newContext({ baseURL: process.env.BASE_URL, viewport: { width: 375, height: 900 } });
  const page = await context.newPage(); const errors: string[] = []; page.on('pageerror', e => errors.push(e.message));
  try {
    const ids = JSON.parse(readFileSync(resolve(root, 'native-file-identities.json'), 'utf8'));
    await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill('r52_admin');
    await page.getByLabel('密码', { exact: true }).fill(readFileSync(resolve(root, 'native/accounts/r52_admin.password'), 'utf8'));
    await page.getByLabel('密码', { exact: true }).press('Enter'); await expect(page).not.toHaveURL(/\/login$/);
    const original = await (await context.request.get(`/api/v1/files/${ids.browser.id}`)).json();
    const movedName = `browser-ui-${Date.now()}.txt`;
    await page.goto(`/app/workspaces/${ids.workspaceId}`);
    const row = (name: string) => page.locator('.file-list > li').filter({ has: page.locator('strong').filter({ hasText: name }) });
    await row(original.name).getByRole('button', { name: '历史版本', exact: true }).click();
    await page.getByRole('button', { name: '只读查看', exact: true }).first().click();
    await expect(page.getByLabel('历史版本只读内容')).toContainText('browser original');
    await page.getByRole('button', { name: '关闭面板', exact: true }).click();
    await row(original.name).getByRole('button', { name: '移动／重命名', exact: true }).click();
    if (await page.getByRole('button', { name: '完成并关闭', exact: true }).isVisible()) {
      await page.getByRole('button', { name: '完成并关闭', exact: true }).click();
      await row(original.name).getByRole('button', { name: '移动／重命名', exact: true }).click();
    }
    await page.getByLabel('名称', { exact: true }).fill(movedName);
    let submitted = 0;
    await page.route('**/api/v1/files/*/move', async route => {
      if (route.request().method() === 'POST') { submitted++; await route.fetch(); await route.abort('failed'); }
      else await route.continue();
    });
    await page.getByRole('button', { name: '确认移动／重命名', exact: true }).click();
    await expect(page.getByText('操作已完成，版本记录已保存。', { exact: true })).toBeVisible({ timeout: 60000 });
    expect(submitted).toBe(1); await page.unrouteAll({ behavior: 'wait' });
    await page.getByRole('button', { name: '完成并关闭', exact: true }).click();
    await page.reload(); await expect(row(movedName)).toBeVisible();
    await row(movedName).getByRole('button', { name: '删除工作副本', exact: true }).click();
    await expect(page.getByText('此次影响 1 项（含所选文件或目录）。', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '确认删除工作副本', exact: true }).click();
    await expect(page.getByText('操作已完成，版本记录已保存。', { exact: true })).toBeVisible({ timeout: 60000 });
    await page.getByRole('button', { name: '完成并关闭', exact: true }).click();
    await page.getByRole('button', { name: '已删除文件', exact: true }).click();
    await page.getByRole('button', { name: '查看／恢复', exact: true }).filter({ visible: true }).first().click();
    await page.getByLabel('名称', { exact: true }).fill('browser-restored.txt');
    await page.getByRole('button', { name: '确认恢复为新版本', exact: true }).click();
    await expect(page.getByText('操作已完成，版本记录已保存。', { exact: true })).toBeVisible({ timeout: 60000 });
    await page.getByRole('button', { name: '完成并关闭', exact: true }).click();
    await expect(row('browser-restored.txt')).toBeVisible();
    const current = await (await context.request.get(`/api/v1/files/${ids.browser.id}`)).json(); expect(current.path).toBe('browser-restored.txt');
    expect(current.versionId).not.toBe(ids.browser.versionId);
    const content = await (await context.request.get(`/api/v1/files/${ids.browser.id}/content?versionId=${ids.browser.versionId}`)).text(); expect(content).toBe('browser original\n');
    for (const width of [375, 768, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      for (const theme of ['light', 'dark']) { await page.getByLabel('主题', { exact: true }).selectOption(theme); await expect(page.locator('html')).toHaveAttribute('data-theme', theme); }
      await page.evaluate(() => { document.body.style.zoom = '2'; }); await expect(row('browser-restored.txt')).toBeVisible();
      await page.keyboard.press('Tab'); await page.evaluate(() => { document.body.style.zoom = '1'; });
    }
    expect(errors).toEqual([]);
    writeFileSync(resolve(root, 'native-browser-result.json'), JSON.stringify({ status: 'PASS', supplierCalls: 0, scope: 'actual API lifecycle/history/read-only preview/delete/restore/original response-loss query,375/768/1440,200%,keyboard/themes', movePosts: submitted }, null, 2));
  } finally { await context.close(); }
});
