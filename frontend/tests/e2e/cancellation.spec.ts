import { test, expect, type Page } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';

test.skip(process.env.R23_BROWSER_ACCEPTANCE !== '1', 'Requires isolated R2.3 API/PG/MQ/two workers and deterministic HTTP provider');
test.use({ trace: 'off', screenshot: 'off' });
test.setTimeout(60000);
const root = process.env.IDENTITY_SECRET_ROOT!;
async function login(page: Page, user = 'alice') {
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill(user);
  await page.getByLabel('密码', { exact: true }).fill(readFileSync(resolve(root, user + '-password'), 'utf8').trim());
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByLabel('模型', { exact: true })).toHaveValue(/.+/);
}
async function send(page: Page, text: string) {
  await page.getByLabel('消息', { exact: true }).fill(text);
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await expect(page).toHaveURL(/\/app\/chat\/[a-f0-9-]+$/);
  return page.url().split('/').at(-1)!;
}
async function lastTask(page: Page, conversation: string) {
  const response = await page.request.get(`/api/v1/conversations/${conversation}/snapshot`);
  return (await response.json()).tasks.at(-1).taskId as string;
}

test('lost cancel response retains partial and read-only UNKNOWN, independent new message excludes the unknown round', async ({ page }) => {
  await login(page);
  const conversation = await send(page, 'fixture:slow:r23:browser:' + randomUUID());
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('慢速');
  const task = await lastTask(page, conversation);
  // Commit on the real API, then discard only the browser's HTTP response.
  await page.route(`**/api/v1/tasks/${task}/cancel`, async route => {
    const response = await route.fetch(); expect(response.status()).toBe(202);
    await route.abort('connectionreset');
  });
  await page.getByRole('button', { name: '取消回复', exact: true }).click();
  const notice = page.getByTestId('reconciliation-notice');
  await expect(notice).toContainText('执行者已停止');
  await expect(notice).toContainText('未进入后续模型上下文');
  await page.unroute(`**/api/v1/tasks/${task}/cancel`);
  await page.reload();
  await expect(notice).toContainText('不会自动重放');
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('慢速');
  const response = await page.request.get(`/api/v1/tasks/${task}/reconciliation`);
  const metadata = await response.json();
  expect(Object.keys(metadata).sort()).toEqual(['calls', 'excludedFromContext', 'stopConfirmed', 'stopStage', 'suggestedAction', 'taskId', 'unknownCalls', 'version'].sort());
  expect(metadata.unknownCalls).toBe(1); expect(metadata.calls[0].inputTokens).toBeNull();
  await expect(page.getByRole('button', { name: /重试|确认对账/ })).toHaveCount(0);
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
  if (shots) { mkdirSync(shots, { recursive: true }); await page.screenshot({ path: resolve(shots, 'r23-unknown-dark-zoom200.png'), fullPage: true }); }
  await page.evaluate(() => { document.documentElement.style.zoom = '1'; });
  await page.getByLabel('消息', { exact: true }).fill('fixture:history:r23:' + randomUUID());
  await page.getByLabel('消息', { exact: true }).press('Control+Enter');
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toHaveText('history-messages=2');
  const old = await (await page.request.get(`/api/v1/tasks/${task}/reconciliation`)).json();
  expect(old.unknownCalls).toBe(1); expect(old.calls).toHaveLength(1);
});

test('queued cancellation sends no provider request and administrator cannot inspect another personal space', async ({ page, browser }) => {
  await login(page);
  const activeConversation = await send(page, 'fixture:slow:r23:queue-owner:' + randomUUID());
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('慢速');
  const other = await browser.newContext();
  try {
    const bob = await other.newPage(); await login(bob, 'bob');
    const conversation = await send(bob, 'fixture:normal:r23:queued:' + randomUUID());
    await expect(bob.getByRole('status').first()).toContainText('排队');
    const task = await lastTask(bob, conversation);
    await bob.getByRole('button', { name: '取消回复', exact: true }).click();
    await expect(bob.getByRole('status').first()).toContainText('已取消');
    const metadata = await (await bob.request.get(`/api/v1/tasks/${task}/reconciliation`)).json();
    expect(metadata.stopConfirmed).toBe(true); expect(metadata.calls).toHaveLength(0);
    expect((await page.request.get(`/api/v1/tasks/${task}/reconciliation`)).status()).toBe(404);
    const active = await lastTask(page, activeConversation);
    expect((await bob.request.get(`/api/v1/tasks/${active}/reconciliation`)).status()).toBe(404);
  } finally {
    await other.close();
    await page.getByRole('button', { name: '取消回复', exact: true }).click();
    await expect(page.getByTestId('reconciliation-notice')).toContainText('执行者已停止');
  }
});
