import { test, expect } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

test.skip(process.env.R12_REAL_MODEL_ACCEPTANCE !== '1', 'Explicit budget-capped real-provider window only');
test.use({ trace: 'off', screenshot: 'off' });

test('real DeepSeek text stream, persisted multi-turn history and token usage', async ({ page, request, baseURL }) => {
  test.setTimeout(180_000);
  expect(baseURL).toBe('http://127.0.0.1:18087');
  const root = resolve(import.meta.dirname, '../../../.cache/r12-local');
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill('alice');
  await page.getByLabel('密码', { exact: true }).fill(readFileSync(resolve(root, 'alice-password'), 'utf8').trim());
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('link', { name: '普通聊天', exact: true }).click();
  await expect(page.getByText('deepseek-flash', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '新建对话' }).click();
  await expect(page).toHaveURL(/\/app\/chat\/[0-9a-f-]+$/);
  const conversation = new URL(page.url()).pathname.split('/').at(-1)!;
  const started = Date.now();
  await page.getByLabel('消息', { exact: true }).fill('请记住暗号“青竹”，然后用约150字介绍持久化消息的作用。不要输出思考过程。');
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await expect(page.getByRole('status')).toContainText(/已完成|失败|已取消|需要处理/, { timeout: 120_000 });
  await expect(page.getByRole('status')).toContainText('已完成');
  const firstDuration = Date.now() - started;
  await expect(page.locator('.chat-message.assistant .chat-text').last()).not.toHaveText('');
  await page.getByLabel('消息', { exact: true }).fill('刚才我请你记住的暗号是什么？只回复暗号。');
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await expect(page.getByRole('status')).toContainText(/已完成|失败|已取消|需要处理/, { timeout: 120_000 });
  await expect(page.getByRole('status')).toContainText('已完成');
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('青竹');
  await page.reload();
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('青竹');
  const snapshot = await page.request.get(`/api/v1/conversations/${conversation}/snapshot`);
  expect(snapshot.status()).toBe(200);
  const body = await snapshot.json();
  expect(body.tasks).toHaveLength(2);
  const usage = [];
  for (const task of body.tasks) {
    expect(task.status).toBe('SUCCEEDED');
    const response = await page.request.get(`/api/v1/tasks/${task.taskId}/usage`);
    expect(response.status()).toBe(200);
    const calls = (await response.json()).items;
    expect(calls).toHaveLength(1);
    expect(calls[0].inputTokens).toBeGreaterThan(0);
    expect(calls[0].outputTokens).toBeGreaterThan(0);
    usage.push(...calls);
  }
  writeFileSync(resolve(root, 'real-model-browser.json'), JSON.stringify({ status: 'PASS', model: 'deepseek-flash',
    tasks: 2, physicalLedgerCalls: usage.length, firstTaskMilliseconds: firstDuration,
    streamProjectionPersisted: true, multiTurnHistory: true, refreshedHistory: true, usage,
    note: 'Cost uses conservative peak cache-miss estimate, not an invoice.' }, null, 2));
});
