import { expect, test, type Page } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

test.skip(process.env.HOME_ENTRY !== '1', 'Requires existing real identity API and explicit flag build');
test.use({ trace: 'off', screenshot: 'off' });
test.setTimeout(60_000);
const chat = process.env.HOME_CHAT_ENABLED === '1';
const cloud = process.env.HOME_CLOUD === '1';
const users = cloud ? ['r1acceptance', 'platformadmin'] : ['alice', 'bob'];
function password(name: string) {
  if (cloud) {
    const data = JSON.parse(readFileSync(resolve(import.meta.dirname, '../../../evidence/r1.4/private/accounts.json'), 'utf8'));
    return data.accounts.find((row: { username: string }) => row.username === name).password as string;
  }
  return readFileSync(resolve(import.meta.dirname, `../../../.cache/r12-local/${name}-password`), 'utf8').trim();
}
async function login(page: Page, name = users[0]!) {
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill(name);
  await page.getByLabel('密码', { exact: true }).fill(password(name));
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(chat ? /\/app\/chat$/ : /\/app\/settings\/account$/);
}
test.beforeEach(async ({ page }) => {
  page.on('request', request => {
    if (request.method() === 'POST' && /\/api\/v1\/(conversations|tasks)(\/|$)/.test(new URL(request.url()).pathname)) {
      throw new Error('Navigation acceptance must not submit chat operations');
    }
  });
});

test('anonymous root, public status, protected app and disabled-chat fallback', async ({ page }) => {
  await page.goto('/');
  if (chat) await expect(page).toHaveURL(/\/login$/);
  else await expect(page.getByRole('heading', { name: '从可靠的基础开始。' })).toBeVisible();
  await page.getByRole('navigation', { name: '主要导航' }).getByRole('link', { name: '连接状态', exact: true }).click();
  await expect(page).toHaveURL(/\/status$/);
  await expect(page.getByRole('status')).toHaveText('连接正常');
  await expect(page.getByRole('heading', { name: chat ? '应用连接状态' : '从可靠的基础开始。' })).toBeVisible();
  await page.goto('/app'); await expect(page).toHaveURL(/\/login$/);
  if (!chat) {
    await page.goto('/app/chat');
    await expect(page.getByRole('heading', { name: '页面不存在。' })).toBeVisible();
  }
});

test('real login landing, refresh, root/app and existing deep-link SSE', async ({ page }) => {
  await login(page);
  const me = await (await page.request.get('/api/v1/me')).json();
  expect(me.username).toBe(users[0]);
  await page.reload();
  await expect(page.getByRole('heading', { name: chat ? '普通聊天' : '我的账号', exact: true })).toBeVisible();
  for (const path of ['/', '/app']) {
    await page.goto(path);
    if (chat || path === '/app') await expect(page).toHaveURL(chat ? /\/app\/chat$/ : /\/app\/settings\/account$/);
    else await expect(page.getByRole('heading', { name: '从可靠的基础开始。' })).toBeVisible();
  }
  if (chat) {
    const list = await (await page.request.get('/api/v1/conversations?limit=100')).json();
    expect(list.items.length, 'Acceptance reuses existing history; never creates a task').toBeGreaterThan(0);
    const id = list.items[0].id;
    const snapshot = await (await page.request.get(`/api/v1/conversations/${id}/snapshot`)).json();
    const stream = page.waitForResponse(response => response.url().includes(`/conversations/${id}/events?`) && response.status() === 200);
    await page.goto(`/app/chat/${id}`); await stream;
    await expect(page).toHaveURL(new RegExp(`/app/chat/${id}$`));
    await expect(page.getByRole('heading', { name: '普通聊天', exact: true })).toBeVisible();
    await page.reload();
    expect((await (await page.request.get(`/api/v1/conversations/${id}/snapshot`)).json()).messages).toEqual(snapshot.messages);
    await page.getByRole('navigation', { name: '主要导航' }).getByRole('link', { name: '账号', exact: true }).click();
    await expect(page.getByRole('heading', { name: '我的账号' })).toBeVisible();
    await page.getByRole('navigation', { name: '主要导航' }).getByRole('link', { name: '聊天', exact: true }).click();
    await expect(page).toHaveURL(/\/app\/chat$/);
  }
});

test('two browser contexts, logout and expired session remain isolated', async ({ page, browser }) => {
  const second = await browser.newContext({ baseURL: process.env.BASE_URL });
  try {
    const other = await second.newPage();
    await login(page); await login(other, users[1]!);
    const mine = await (await page.request.get('/api/v1/me')).json();
    const theirs = await (await second.request.get('/api/v1/me')).json();
    expect(mine.id).not.toBe(theirs.id);
    expect((await page.request.get('/api/v1/me', { headers: { 'X-Space-Id': theirs.spaces[0].id } })).status()).toBe(404);
    await page.goto('/app/settings/account');
    await page.getByRole('button', { name: '退出登录', exact: true }).click();
    await expect(page).toHaveURL(/\/login$/);
    expect((await page.request.get('/api/v1/me')).status()).toBe(401);
    expect((await second.request.get('/api/v1/me')).status()).toBe(200);
    await second.clearCookies(); await other.reload();
    await expect(other).toHaveURL(/\/login$/);
    await page.goto('/app'); await expect(page).toHaveURL(/\/login$/);
  } finally { await second.close(); }
});

test('header navigation supports responsive themes, keyboard and 200 percent zoom', async ({ page }) => {
  await login(page);
  for (const width of [375, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    for (const theme of ['light', 'dark']) {
      await page.getByLabel('主题', { exact: true }).selectOption(theme);
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      const target = await page.getByRole('navigation', { name: '主要导航' }).getByRole('link', { name: '连接状态', exact: true }).boundingBox();
      expect(target!.height).toBeGreaterThanOrEqual(44);
    }
  }
  await page.setViewportSize({ width: 375, height: 900 });
  await page.evaluate(() => { document.documentElement.style.zoom = '2'; });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole('navigation', { name: '主要导航' }).getByRole('link', { name: '连接状态', exact: true }).click();
  await expect(page).toHaveURL(/\/status$/);
  await page.evaluate(() => { document.documentElement.style.zoom = '1'; });
  await page.goto('/status'); await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: '跳到主要内容' })).toBeFocused();
  if (process.env.HOME_EVIDENCE) {
    mkdirSync(process.env.HOME_EVIDENCE, { recursive: true });
    await page.screenshot({ path: resolve(process.env.HOME_EVIDENCE, `navigation-${chat ? 'chat' : 'baseline'}.png`), fullPage: true });
  }
});

test('authentication dependency failure rejects access without redirect loops', async ({ page }) => {
  let requests = 0;
  await page.route('**/api/v1/me', route => {
    requests++;
    return route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify({ title: 'Unavailable', status: 503, code: 'DEPENDENCY_UNAVAILABLE' }) });
  });
  await page.goto(chat ? '/' : '/app');
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible();
  await page.waitForTimeout(500);
  expect(requests).toBe(1);
});
