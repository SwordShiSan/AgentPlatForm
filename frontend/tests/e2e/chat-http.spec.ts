import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

test.skip(process.env.R14_BROWSER_ACCEPTANCE !== '1', 'R1.4 isolated API/fixture required');
test.use({ trace: 'off', screenshot: 'off' });
test('submit and cancel work when public-HTTP-only browser capabilities are available', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(globalThis.crypto, 'randomUUID', { value: undefined });
    Object.defineProperty(navigator, 'clipboard', { value: undefined });
  });
  await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill('bob');
  await page.getByLabel('密码', { exact: true }).fill(readFileSync(resolve(import.meta.dirname, '../../../.cache/r12-local/bob-password'), 'utf8').trim());
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '普通聊天', exact: true })).toBeVisible();
  await expect(page.getByLabel('模型', { exact: true })).toHaveValue(/.+/);
  await page.getByLabel('消息', { exact: true }).fill('fixture:slow');
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await expect(page).toHaveURL(/\/app\/chat\/[a-f0-9-]+$/);
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('慢速');
  await page.getByRole('button', { name: '取消回复', exact: true }).click();
  await expect(page.getByRole('status').first()).toContainText('已取消');
  const panel = page.getByRole('region', { name: '任务诊断' });
  await panel.getByRole('button', { name: '复制诊断编号' }).click();
  await expect(panel.getByRole('status')).toContainText('手动复制');
});
