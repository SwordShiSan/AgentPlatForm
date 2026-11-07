import { test, expect, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

test.skip(process.env.R62_BROWSER_ACCEPTANCE !== '1', 'Requires the isolated R6.2 control-plane fixture');
test.use({ trace: 'off', screenshot: 'off' });
test.setTimeout(120_000);
const root = resolve(import.meta.dirname, '../../../.cache/r62-local/fixture');
const skillName = process.env.R62_BROWSER_SKILL_NAME ?? 'r62-browser-skill';

async function login(page: Page, admin = true) {
  await page.goto('/'); await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel('账号', { exact: true }).fill(admin ? 'r62_admin' : 'r62_user');
  await page.getByLabel('密码', { exact: true }).fill(readFileSync(resolve(root, admin ? 'admin-password' : 'user-password'), 'utf8').trim());
  const request = page.waitForResponse(response => response.url().endsWith('/api/v1/auth/login') && response.request().method() === 'POST', { timeout: 30_000 });
  await page.getByRole('button', { name: '登录', exact: true }).click();
  expect((await request).status()).toBe(200); await expect(page).toHaveURL(/\/app\/chat$/, { timeout: 30_000 });
}

test('import, publish and bind an immutable skill version', async ({ page }) => {
  await login(page); await page.goto('/app/settings/capabilities');
  await expect(page.getByRole('heading', { name: '能力管理' })).toBeVisible();
  await page.getByLabel('Skill 压缩包').setInputFiles(resolve(root, 'browser-skill.zip'));
  await page.getByRole('button', { name: '开始导入' }).click();
  const query = page.getByRole('button', { name: '查询原请求' });
  await expect(query).toBeVisible();
  await expect.poll(async () => {
    await query.click();
    return await page.getByText(/状态：/).first().textContent();
  }, { timeout: 60_000, intervals: [1000, 2000] }).toContain('DRAFT');
  await page.getByRole('button', { name: '完成并关闭' }).click();
  await page.getByRole('button', { name: skillName, exact: true }).click();
  await page.getByRole('button', { name: /版本 1 — DRAFT/ }).click();
  await page.getByRole('button', { name: '发布版本' }).click();
  await expect(page.getByRole('heading', { name: `${skillName} — PUBLISHED` })).toBeVisible();
  await page.getByRole('button', { name: '选择此版本用于绑定' }).click();
  await page.getByRole('button', { name: '添加当前查看的版本' }).click();
  await page.getByRole('button', { name: '保存选择' }).click();
  await expect(page.getByText('能力选择已保存。')).toBeVisible({ timeout: 30_000 });
  await page.reload(); await expect(page.getByRole('heading', { name: '能力绑定' })).toBeVisible();
  await expect(page.getByRole('button', { name: '移除' })).toHaveCount(1);
  for (const width of [375, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
  }
  await page.getByLabel('Skill 压缩包').focus(); await expect(page.getByLabel('Skill 压缩包')).toBeFocused();
  await page.evaluate(() => { document.documentElement.style.zoom = '2'; });
  await expect(page.getByRole('heading', { name: '能力管理' })).toBeVisible();
  await page.evaluate(() => { document.documentElement.style.zoom = ''; });
});

test('connector metadata and secret stay private to the owning account', async ({ page, browser }) => {
  await login(page); await page.goto('/app/settings/capabilities');
  const sentinel = 'R62_browser_fixture_secret_6289';
  await page.getByLabel('新密钥').fill(sentinel);
  const saved = page.waitForResponse(response => response.url().includes('/api/v1/me/connector-credentials') && response.request().method() === 'POST');
  await page.getByRole('button', { name: '保存密钥' }).click(); const response = await saved; expect(response.ok()).toBeTruthy();
  const credential = await response.json(); expect(JSON.stringify(credential)).not.toContain(sentinel);
  await expect(page.getByLabel('新密钥')).toHaveValue('');
  expect(await page.evaluate(() => JSON.stringify(sessionStorage))).not.toContain(sentinel);
  await page.getByLabel('HTTPS 地址').fill('https://fixture.invalid/mcp');
  await page.getByRole('button', { name: '保存连接器' }).click();
  await expect(page.getByRole('button', { name: 'https://fixture.invalid/mcp' })).toBeVisible();
  const other = await browser.newContext(); const otherPage = await other.newPage();
  await login(otherPage, false); const list = await otherPage.request.get('/api/v1/me/connector-credentials');
  expect(list.ok()).toBeTruthy(); expect(JSON.stringify(await list.json())).not.toContain(credential.id);
  await other.close();
});
