import { test, expect } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
test.skip(process.env.R13_REAL_MODEL_ACCEPTANCE !== '1', 'Explicit shared-budget real provider window');
test.use({ trace: 'off', screenshot: 'off' });
test('real Flash/Pro and platform/user credentials preserve one multi-turn conversation', async ({ page, baseURL }) => {
  test.setTimeout(480_000); expect(baseURL).toBe('http://127.0.0.1:18087');
  const root = resolve(import.meta.dirname, '../../../.cache/r12-local');
  const username = process.env.R13_ACCEPTANCE_USER ?? 'r13acceptance';
  await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(readFileSync(resolve(root, `${username}-password`), 'utf8').trim()); await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('link', { name: '普通聊天', exact: true }).click(); await page.getByRole('button', { name: '新建对话' }).click();
  const results = [];
  for (const [index, [model, source]] of [['deepseek-flash', 'PLATFORM'], ['deepseek-v4-pro', 'PLATFORM'], ['deepseek-v4-pro', 'USER'], ['deepseek-flash', 'USER']].entries()) {
    if (index === 2) {
      await page.getByText('模型密钥', { exact: true }).click();
      let key = readFileSync('D:/java/Projects/Keys/deepseek_api_key.txt', 'utf8').replace(/^\uFEFF/, '').trim();
      try { await page.getByLabel('新的 DeepSeek API key', { exact: true }).fill(key); await page.getByRole('button', { name: '保存或替换个人密钥' }).click(); await expect(page.getByRole('status').last()).toContainText('已加密保存'); }
      finally { key = ''; }
      await expect(page.getByLabel('新的 DeepSeek API key', { exact: true })).toHaveValue('');
    }
    await page.getByLabel('模型', { exact: true }).selectOption({ label: model }); await page.getByLabel('凭据来源', { exact: true }).selectOption(source);
    await page.getByLabel('消息', { exact: true }).fill(index === 0 ? '请记住暗号青竹，只回复“已记住青竹”。不要输出思考过程。' : '我刚才请你记住的暗号是什么？只回复暗号，不要输出思考过程。');
    await page.getByRole('button', { name: '发送', exact: true }).click();
    await expect(page.locator('.chat-message.assistant')).toHaveCount(index + 1); await expect(page.getByRole('status').first()).toContainText(/已完成|失败|需要处理/, { timeout: 125_000 });
    await expect(page.getByRole('status').first()).toContainText('已完成'); await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('青竹');
    const conversation = page.url().split('/').at(-1)!; const snapshot = await (await page.request.get(`/api/v1/conversations/${conversation}/snapshot`)).json();
    const task = snapshot.tasks.at(-1); const config = await (await page.request.get(`/api/v1/tasks/${task.taskId}/configuration`)).json(); const usage = await (await page.request.get(`/api/v1/tasks/${task.taskId}/usage`)).json();
    expect(config.values.modelName).toBe(model); expect(config.values.credentialSource).toBe(source); expect(usage.items).toHaveLength(1); expect(usage.items[0].inputTokens).toBeGreaterThan(0); expect(usage.items[0].outputTokens).toBeGreaterThan(0);
    results.push({ model, source, taskId: task.taskId, configHash: config.configHash, usage: usage.items });
  }
  await page.reload(); await expect(page.locator('.chat-message.assistant')).toHaveCount(4); await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('青竹');
  writeFileSync(resolve(root, 'r13-real-browser.json'), JSON.stringify({ status: 'PASS', physicalCalls: results.length, results, estimateNotInvoice: true }, null, 2));
});
