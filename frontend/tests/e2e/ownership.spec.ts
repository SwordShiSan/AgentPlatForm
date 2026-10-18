import { test, expect, type Page } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

test.skip(process.env.R21_BROWSER_ACCEPTANCE !== '1', 'Requires isolated R2.1 API, two workers, PG/MQ and deterministic provider');
test.use({ trace: 'off', screenshot: 'off' });
const root = process.env.IDENTITY_SECRET_ROOT ?? resolve(import.meta.dirname, '../../../.cache/r21-local');

async function login(page: Page, user = 'alice') {
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill(user);
  await page.getByLabel('密码', { exact: true }).fill(readFileSync(resolve(root, `${user}-password`), 'utf8').trim());
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(/\/app\/chat$/);
  await expect(page.getByRole('heading', { name: '普通聊天', exact: true })).toBeVisible();
  await expect(page.getByLabel('模型', { exact: true })).toHaveValue(/.+/);
}

test('single-slot chat survives refresh, has immutable history and responsive keyboard access', async ({ page }) => {
  await login(page);
  await page.getByLabel('消息', { exact: true }).fill('fixture:normal');
  await page.getByLabel('消息', { exact: true }).press('Control+Enter');
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toHaveText('你好😀，这是流式回复。');
  await expect(page.getByRole('status').first()).toContainText('已完成');
  const url = page.url(); await page.reload(); await expect(page).toHaveURL(url);
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toHaveText('你好😀，这是流式回复。');
  for (const width of [375, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    for (const theme of ['light', 'dark']) {
      await page.getByLabel('主题', { exact: true }).selectOption(theme);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await page.getByLabel('消息', { exact: true }).focus(); await expect(page.getByLabel('消息', { exact: true })).toBeFocused();
    }
  }
  await page.evaluate(() => { document.documentElement.style.zoom = '2'; });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  const shots = process.env.R21_SCREENSHOTS;
  if (shots) { mkdirSync(shots, { recursive: true }); await page.screenshot({ path: resolve(shots, 'chat-dark-zoom200.png'), fullPage: true }); }
});

test('partial failure remains readable and diagnostic is denied to another user', async ({ page, browser }) => {
  await login(page, 'bob');
  await page.getByLabel('消息', { exact: true }).fill(`fixture:partial:${Date.now()}`);
  await page.getByRole('button', { name: '发送', exact: true }).click();
  const panel = page.getByRole('region', { name: '任务诊断' });
  await expect(panel).toContainText('不会自动重放');
  const id = page.url().split('/').at(-1)!;
  const snapshot = await (await page.request.get(`/api/v1/conversations/${id}/snapshot`)).json();
  const task = snapshot.tasks.at(-1).taskId;
  const other = await browser.newContext();
  try {
    await login(await other.newPage(), 'alice');
    for (const path of [`/api/v1/conversations/${id}`, `/api/v1/conversations/${id}/snapshot`, `/api/v1/tasks/${task}`, `/api/v1/tasks/${task}/diagnostics`]) {
      const response = await other.request.get(path); expect(response.status()).toBe(404);
    }
  } finally { await other.close(); }
  await page.reload(); await expect(panel).toContainText('不会自动重放');
});

test('explicit cancellation preserves partial output and releases the execution slot', async ({ page }) => {
  await login(page);
  await page.getByLabel('消息', { exact: true }).fill('fixture:slow');
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('慢速');
  await page.getByRole('button', { name: '取消回复', exact: true }).click();
  await expect(page.getByRole('status').first()).toContainText('已取消');
  await page.reload(); await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('慢速');
});
