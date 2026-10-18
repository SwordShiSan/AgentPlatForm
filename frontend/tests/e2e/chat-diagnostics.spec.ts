import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

test.skip(process.env.R14_BROWSER_ACCEPTANCE !== '1', 'R1.4 isolated real API/fixture required');
test.use({ trace: 'off', screenshot: 'off' });

test('partial unknown result has owner-only diagnostic and no automatic retry', async ({ page, browser }) => {
  const root = resolve(import.meta.dirname, '../../../.cache/r12-local');
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill('bob');
  await page.getByLabel('密码', { exact: true }).fill(readFileSync(resolve(root, 'bob-password'), 'utf8').trim());
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '普通聊天', exact: true })).toBeVisible();
  await expect(page.getByLabel('模型', { exact: true })).toHaveValue(/.+/);
  await page.getByLabel('模型', { exact: true }).selectOption({ label: 'deepseek-flash' });
  await page.getByLabel('凭据来源', { exact: true }).selectOption('PLATFORM');
  await page.getByLabel('消息', { exact: true }).fill(`fixture:partial:${Date.now()}`);
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await expect(page).toHaveURL(/\/app\/chat\/[0-9a-f-]+$/);
  const panel = page.getByRole('region', { name: '任务诊断' });
  await expect(panel).toContainText('不会自动重放');
  await expect(panel).toContainText('已保留部分回复');
  await panel.getByRole('button', { name: '复制诊断编号' }).click();
  await expect(panel.getByRole('status')).toContainText(/已复制|手动复制/);
  const conversation = page.url().split('/').at(-1)!;
  const snapshot = await (await page.request.get(`/api/v1/conversations/${conversation}/snapshot`)).json();
  const task = snapshot.tasks.at(-1).taskId;
  const diagnostic = await (await page.request.get(`/api/v1/tasks/${task}/diagnostics`)).json();
  expect(diagnostic.classification).toBe('UNKNOWN_SIDE_EFFECT');
  expect(diagnostic.automaticReplayAllowed).toBe(false);
  expect(diagnostic.traceId).toMatch(/^[a-f0-9-]{36}$/);
  const other = await browser.newContext();
  try {
    const second = await other.newPage(); await second.goto('/login');
    await second.getByLabel('账号', { exact: true }).fill('alice');
    await second.getByLabel('密码', { exact: true }).fill(readFileSync(resolve(root, 'alice-password'), 'utf8').trim());
    await second.getByRole('button', { name: '登录', exact: true }).click();
    await expect(second.getByRole('heading', { name: '普通聊天', exact: true })).toBeVisible();
    const rejected = await other.request.get(`/api/v1/tasks/${task}/diagnostics`);
    expect(rejected.status()).toBe(404);
    expect(await rejected.text()).not.toContain(diagnostic.traceId);
  } finally { await other.close(); }
  await page.reload(); await expect(panel).toContainText(diagnostic.traceId);
  const screenshots = resolve(import.meta.dirname, '../ui-evidence'); mkdirSync(screenshots, { recursive: true });
  for (const theme of ['light', 'dark']) {
    await page.getByLabel('主题', { exact: true }).selectOption(theme);
    await page.screenshot({ path: resolve(screenshots, `r1.4-diagnostics-${theme}.png`), fullPage: true });
  }
});
