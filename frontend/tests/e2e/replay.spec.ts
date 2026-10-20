import { test, expect, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

test.skip(process.env.R22_BROWSER_ACCEPTANCE !== '1', 'Requires isolated R2.2 actual API/PG/MQ and deterministic provider');
test.use({ trace: 'off', screenshot: 'off' });
test.setTimeout(60000);
const root = process.env.IDENTITY_SECRET_ROOT ?? resolve(import.meta.dirname, '../../../.cache/r22-local');
async function login(page: Page, user = 'alice') {
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill(user);
  await page.getByLabel('密码', { exact: true }).fill(readFileSync(resolve(root, `${user}-password`), 'utf8').trim());
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(/\/app\/chat$/);
  await expect(page.getByLabel('模型', { exact: true })).toHaveValue(/.+/);
}
async function submit(page: Page, prompt = 'fixture:normal') {
  await page.getByLabel('消息', { exact: true }).fill(prompt);
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await expect(page).toHaveURL(/\/app\/chat\/[0-9a-f-]+$/);
  return page.url().split('/').at(-1)!;
}
test('offline resumes only applied events and never submits a second task', async ({ page, context }) => {
  await login(page);
  let submissions = 0;
  page.on('request', request => { if (request.method() === 'POST' && /\/conversations(?:\/[^/]+\/tasks)?$/.test(new URL(request.url()).pathname)) submissions++; });
  const id = await submit(page, 'fixture:slow');
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('慢速');
  await context.setOffline(true);
  await expect(page.getByText('网络已断开，联网后继续读取。', { exact: true })).toBeVisible();
  await context.setOffline(false);
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('慢速');
  await page.reload();
  const snapshot = await (await page.request.get(`/api/v1/conversations/${id}/snapshot`)).json();
  expect(snapshot.tasks).toHaveLength(1);
  expect(submissions).toBe(1);
  // The only cancellation is this explicit UI action, after replay assertions.
  const cancel = page.getByRole('button', { name: '取消回复', exact: true });
  if (await cancel.isVisible()) { await cancel.click(); await expect(page.getByRole('status').first()).toContainText('已取消'); }
});
test('expired cursor repairs from a real snapshot once and route switching keeps histories separate', async ({ page }) => {
  await login(page, 'bob');
  const first = await submit(page);
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toHaveText('你好😀，这是流式回复。');
  let repaired = 0;
  await page.route(`**/api/v1/conversations/${first}/events?*`, async route => {
    if (repaired++ === 0) await route.fulfill({ status: 410, contentType: 'application/problem+json', body: JSON.stringify({ code: 'CURSOR_EXPIRED' }) });
    else await route.continue();
  });
  await page.reload();
  await expect.poll(() => repaired).toBeGreaterThanOrEqual(2);
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toHaveText('你好😀，这是流式回复。');
  await page.getByRole('button', { name: '新建对话', exact: true }).click();
  expect(await page.locator('.chat-message').count()).toBe(0);
  await page.getByLabel('消息', { exact: true }).fill('isolated draft');
  await page.goto(`/app/chat/${first}`);
  await expect(page.locator('.chat-message.assistant')).toHaveCount(1);
  await expect(page.getByLabel('消息', { exact: true })).not.toHaveValue('isolated draft');
});
