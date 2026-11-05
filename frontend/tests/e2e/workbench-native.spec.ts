import { test, expect, type Page } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

test.use({ trace: 'off', screenshot: 'off' });
test.skip(process.env.R54_NATIVE !== '1', 'Explicit R54 isolated real API only');
const root = resolve(process.cwd(), '../.cache/r54-local');
async function login(page: Page, user: string) {
  await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill(user);
  await page.getByLabel('密码', { exact: true }).fill(readFileSync(resolve(root, `native/accounts/${user}.password`), 'utf8').trim());
  await page.getByLabel('密码', { exact: true }).press('Enter'); await expect(page).not.toHaveURL(/\/login$/);
}

test('real chat sources, attachment refresh, responsive workbench, storage and account isolation', async ({ browser }) => {
  test.setTimeout(240000);
  const seed = JSON.parse(readFileSync(resolve(root, 'native-attachment-result.json'), 'utf8'));
  const context = await browser.newContext({ baseURL: process.env.BASE_URL, viewport: { width: 1440, height: 900 } });
  const foreign = await browser.newContext({ baseURL: process.env.BASE_URL });
  const page = await context.newPage(); const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  page.setDefaultNavigationTimeout(20000); page.setDefaultTimeout(20000);
  console.info('R54_BROWSER_PAGE_READY');
  try {
    await login(page, 'r54_admin'); console.info('R54_BROWSER_LOGIN_OK'); await page.goto(`/app/chat/${seed.conversationId}`);
    await expect(page.getByTestId('context-capacity')).toContainText('262,144');
    await expect(page.getByLabel('固定附件来源')).toContainText('large-context.txt');
    await expect(page.getByLabel('消息历史')).toContainText('R54_FIXED_SOURCE_666');
    console.info('R54_BROWSER_SOURCE_VISIBLE'); await page.getByRole('button', { name: '预览固定来源', exact: true }).click();
    const viewer = page.getByRole('region', { name: '固定版本文件预览' });
    await expect(viewer.getByLabel('历史版本只读内容')).toContainText('R54_FIXED_SOURCE_666', { timeout: 125000 });
    await page.getByRole('button', { name: '关闭预览', exact: true }).click();
    await page.locator('.attachment-composer input[type=file]').setInputFiles(resolve(root, 'preview-fixtures/sample.txt'));
    await expect(page.locator('.attachment-composer')).toContainText('正文已就绪', { timeout: 125000 });
    await page.reload(); await expect(page.locator('.attachment-composer')).toContainText('正文已就绪');
    for (const width of [375, 768, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      await page.getByRole('navigation', { name: '工作台布局' }).getByRole('button', { name: '聊天', exact: true }).click();
      await expect(page.getByLabel('消息', { exact: true })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2)).toBe(true);
      await page.keyboard.press('Tab');
    }
    await page.evaluate(() => { document.documentElement.dataset.theme = 'dark'; document.documentElement.style.zoom = '2'; });
    await expect(page.getByRole('button', { name: '上传附件', exact: true })).toBeVisible();
    await page.evaluate(() => { document.documentElement.style.zoom = ''; document.documentElement.dataset.theme = 'light'; });
    await page.goto('/app/storage'); await expect(page.getByRole('heading', { name: '数据与存储' })).toBeVisible();
    await expect(page.getByRole('region', { name: '对象用量' })).toContainText('聊天附件原件');
    const other = await foreign.newPage(); await login(other, 'r54_user');
    const denied = await other.evaluate(async id => (await fetch(`/api/v1/conversations/${id}`)).status, seed.conversationId);
    expect([403, 404]).toContain(denied);
    expect(errors).toEqual([]);
    writeFileSync(resolve(root, 'native-workbench-browser-result.json'), JSON.stringify({ status: 'PASS', supplierCalls: 0,
      scope: 'Actual API pinned attachment preview, metadata-only refresh recovery, 375/768/1440, 200%, keyboard, themes, storage and second-user denial' }, null, 2));
  } finally { await Promise.allSettled([foreign.close(), context.close()]); }
});
