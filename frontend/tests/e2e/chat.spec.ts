import { test, expect, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

test.skip(process.env.R12_BROWSER_ACCEPTANCE !== '1', 'Requires isolated real R1.2 API/MQ/PG and deterministic provider');
test.use({ trace: 'off', screenshot: 'off' });
const secretRoot = resolve(import.meta.dirname, '../../../.cache/r12-local');
async function login(page: Page, name = 'alice') {
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill(name);
  await page.getByLabel('密码', { exact: true }).fill(readFileSync(resolve(secretRoot, `${name}-password`), 'utf8').trim());
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(/\/app\/settings\/account$/);
  await page.getByRole('link', { name: '普通聊天', exact: true }).click();
  await expect(page.getByRole('heading', { name: '普通聊天', exact: true })).toBeVisible();
}

test('real queued reply, keyboard submission, persisted refresh and responsive themes', async ({ page, baseURL }) => {
  expect(baseURL).toBe('http://127.0.0.1:18087');
  await login(page);
  await page.getByRole('button', { name: '新建对话' }).click();
  await expect(page).toHaveURL(/\/app\/chat$/);
  await page.getByLabel('消息', { exact: true }).fill('fixture:normal');
  await page.getByLabel('消息', { exact: true }).press('Control+Enter');
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toHaveText('你好😀，这是流式回复。');
  await expect(page.getByRole('status')).toContainText('已完成');
  await page.reload();
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toHaveText('你好😀，这是流式回复。');
  for (const theme of ['light', 'dark']) {
    await page.getByLabel('主题', { exact: true }).selectOption(theme);
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await expect(page.getByLabel('消息', { exact: true })).toBeVisible();
  }
});

test('first committed text is visible while running and explicit cancel keeps partial history', async ({ page }) => {
  await login(page);
  await page.getByRole('button', { name: '新建对话' }).click();
  await page.getByLabel('消息', { exact: true }).fill('fixture:slow');
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('慢速');
  await expect(page.getByRole('status')).toContainText('正在回复');
  await page.getByRole('button', { name: '取消回复', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('已取消');
  await expect(page.locator('.chat-message.assistant .eyebrow').last()).toContainText('回复未完成');
});

test('two browser sessions cannot read or cancel each other\'s conversation', async ({ browser, baseURL }) => {
  const first = await browser.newContext({ baseURL, recordVideo: undefined });
  const second = await browser.newContext({ baseURL, recordVideo: undefined });
  try {
    const alice = await first.newPage(); const bob = await second.newPage();
    await login(alice, 'alice'); await login(bob, 'bob');
    await alice.getByRole('button', { name: '新建对话' }).click();
    await alice.getByLabel('消息', { exact: true }).fill('fixture:normal');
    await alice.getByRole('button', { name: '发送', exact: true }).click();
    await expect(alice).toHaveURL(/\/app\/chat\/[0-9a-f-]+$/);
    const conversation = new URL(alice.url()).pathname.split('/').at(-1)!;
    expect((await second.request.get(`/api/v1/conversations/${conversation}/snapshot`)).status()).toBe(404);
    expect((await second.request.get(`/api/v1/conversations/${conversation}/events?after=0`)).status()).toBe(404);
    await alice.getByLabel('消息', { exact: true }).fill('fixture:normal');
    await alice.getByRole('button', { name: '发送', exact: true }).click();
    await expect(alice.getByRole('status').first()).toContainText('已完成');
    const snapshot = await (await first.request.get(`/api/v1/conversations/${conversation}/snapshot`)).json();
    const task = snapshot.tasks.at(-1).taskId;
    expect((await second.request.get(`/api/v1/tasks/${task}/usage`)).status()).toBe(404);
    const csrf = await (await second.request.get('/api/v1/auth/csrf')).json();
    expect((await second.request.post(`/api/v1/tasks/${task}/cancel`, { data: {}, headers: { Origin: baseURL!, 'X-CSRF-Token': csrf.csrfToken, 'Idempotency-Key': crypto.randomUUID() } })).status()).toBe(404);
    await bob.getByRole('button', { name: '新建对话' }).click();
    await bob.getByLabel('消息', { exact: true }).fill('fixture:normal');
    await bob.getByRole('button', { name: '发送', exact: true }).click();
    await expect(bob).toHaveURL(/\/app\/chat\/[0-9a-f-]+$/);
    const privateConversation = new URL(bob.url()).pathname.split('/').at(-1)!;
    // Alice is a governance administrator; that status never grants Bob's content.
    expect((await first.request.get(`/api/v1/conversations/${privateConversation}`)).status()).toBe(404);
    expect((await first.request.get(`/api/v1/conversations/${privateConversation}/snapshot`)).status()).toBe(404);
    expect((await first.request.get(`/api/v1/conversations/${privateConversation}/events?after=0`)).status()).toBe(404);
  } finally { await first.close(); await second.close(); }
});

test('browser disconnection does not cancel the task and reconnect reconstructs committed text once', async ({ page, context }) => {
  test.setTimeout(60_000);
  await login(page);
  await page.getByRole('button', { name: '新建对话' }).click();
  await page.getByLabel('消息', { exact: true }).fill('fixture:slow');
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('慢速');
  await context.setOffline(true);
  await page.waitForTimeout(2_000);
  await context.setOffline(false);
  await expect(page.getByRole('status')).toContainText('已完成', { timeout: 40_000 });
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toHaveText('慢速'.repeat(50));
  await page.reload();
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toHaveText('慢速'.repeat(50));
});
