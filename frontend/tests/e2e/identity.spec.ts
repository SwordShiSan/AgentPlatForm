import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

test.skip(process.env.R11_IDENTITY !== '1', 'Requires isolated R1.1 real API fixture');
const secret = (user: string) => readFileSync(resolve(process.cwd(), '../.cache/r11-local', `${user}-password`), 'utf8').trim();

test('real login, refresh, two-user isolation, logout and protected route', async ({ browser }) => {
  const a = await browser.newContext();
  const b = await browser.newContext();
  try {
    const alice = await a.newPage();
    const bob = await b.newPage();
    for (const [page, name] of [[alice, 'alice'], [bob, 'bob']] as const) {
      await page.goto('/app/settings/account');
      await expect(page).toHaveURL(/\/login$/);
      await page.getByLabel('账号', { exact: true }).fill(name);
      await page.getByLabel('密码', { exact: true }).fill(secret(name));
      await page.getByRole('button', { name: '登录', exact: true }).click();
      await expect(page.getByRole('heading', { name: '我的账号' })).toBeVisible();
      await expect(page.locator('.user-menu')).toContainText(name);
      await page.reload();
      await expect(page.locator('.user-menu')).toContainText(name);
    }
    const mine = await (await a.request.get('/api/v1/me')).json();
    const other = await (await b.request.get('/api/v1/me')).json();
    expect(mine.id).not.toBe(other.id);
    expect((await a.request.get('/api/v1/me', { headers: { 'X-Space-Id': other.spaces[0].id } })).status()).toBe(404);
    expect((await b.request.get('/api/v1/me', { headers: { 'X-Space-Id': mine.spaces[0].id } })).status()).toBe(404);
    await alice.getByRole('button', { name: '退出登录' }).click();
    await expect(alice).toHaveURL(/\/login$/);
    await alice.goto('/app/settings/account');
    await expect(alice).toHaveURL(/\/login$/);
    await expect(bob.locator('.user-menu')).toContainText('bob');
  } finally { await a.close(); await b.close(); }
});

test('login errors, keyboard, responsive themes and session loss', async ({ page, context }) => {
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill('bob');
  await page.getByLabel('密码', { exact: true }).fill('wrong-fixture-password');
  await page.getByLabel('密码', { exact: true }).press('Enter');
  await expect(page.getByRole('alert')).toHaveText('账号或密码不正确，请重试。');
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue('');
  await page.getByLabel('密码', { exact: true }).fill(secret('bob'));
  await page.getByLabel('密码', { exact: true }).press('Enter');
  await expect(page.getByRole('heading', { name: '我的账号' })).toBeVisible();
  for (const width of [375, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    for (const theme of ['light', 'dark']) {
      await page.getByLabel('主题').selectOption(theme);
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await page.screenshot({ path: `test-results/identity-${width}-${theme}.png`, fullPage: true });
    }
  }
  await context.clearCookies();
  await page.reload();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.locator('.user-menu')).toHaveCount(0);
});
