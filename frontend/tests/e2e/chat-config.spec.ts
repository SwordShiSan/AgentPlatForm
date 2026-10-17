import { test, expect, type Page } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
test.skip(process.env.R13_BROWSER_ACCEPTANCE !== '1', 'Isolated local API and fixture required');
test.use({ trace: 'off', screenshot: 'off' });
async function login(page: Page, name = 'bob') {
  await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill(name);
  await page.getByLabel('密码', { exact: true }).fill(readFileSync(resolve(import.meta.dirname, '../../../.cache/r12-local', `${name}-password`), 'utf8').trim());
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('link', { name: '普通聊天', exact: true }).click();
  await expect(page.getByLabel('模型', { exact: true })).toHaveValue(/.+/);
}
test('draft refresh, first-message commit and Flash to Pro snapshots', async ({ page }) => {
  await login(page); let creates = 0;
  page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/api/v1/conversations')) creates++; });
  await page.getByRole('button', { name: '新建对话' }).click();
  await page.getByLabel('消息', { exact: true }).fill('fixture:normal'); await page.reload();
  await expect(page.getByLabel('消息', { exact: true })).toHaveValue('fixture:normal'); expect(creates).toBe(0);
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await expect(page).toHaveURL(/\/app\/chat\/[0-9a-f-]+$/); await expect(page.getByRole('status').first()).toContainText('已完成'); expect(creates).toBe(1);
  const conversation = page.url().split('/').at(-1)!; const before = await (await page.request.get(`/api/v1/conversations/${conversation}/snapshot`)).json();
  const original = await (await page.request.get(`/api/v1/tasks/${before.tasks[0].taskId}/configuration`)).json();
  await page.getByLabel('模型', { exact: true }).selectOption({ label: 'deepseek-v4-pro' });
  await page.getByLabel('消息', { exact: true }).fill('fixture:normal'); await page.getByLabel('消息', { exact: true }).press('Control+Enter');
  await expect(page.locator('.chat-message.assistant')).toHaveCount(2); await expect(page.getByRole('status').first()).toContainText('已完成');
  const after = await (await page.request.get(`/api/v1/conversations/${conversation}/snapshot`)).json();
  expect(await (await page.request.get(`/api/v1/tasks/${before.tasks[0].taskId}/configuration`)).json()).toEqual(original);
  const second = await (await page.request.get(`/api/v1/tasks/${after.tasks[1].taskId}/configuration`)).json(); expect(second.values.modelName).toBe('deepseek-v4-pro');
  const screenshots = resolve(import.meta.dirname, '../ui-evidence'); mkdirSync(screenshots, { recursive: true });
  for (const width of [375, 768, 1440]) { await page.setViewportSize({ width, height: 900 }); for (const theme of ['light', 'dark']) { await page.getByLabel('主题', { exact: true }).selectOption(theme); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true); if (width === 1440) await page.screenshot({ path: resolve(screenshots, `r1.3-chat-${theme}.png`), fullPage: true }); } }
  await page.evaluate(() => { document.documentElement.style.zoom = '2'; }); await expect(page.getByLabel('消息', { exact: true })).toBeVisible();
});
test('uncertain first response survives refresh and same-key replay creates one task', async ({ page }) => {
  await login(page); let accepted: { id: string } | undefined; let lost = false;
  await page.route('**/api/v1/conversations', async route => { if (route.request().method() === 'POST' && !lost) { lost = true; accepted = await (await route.fetch()).json(); await route.abort(); } else await route.continue(); });
  await page.getByLabel('消息', { exact: true }).fill('fixture:normal'); await page.getByRole('button', { name: '发送', exact: true }).click();
  await expect.poll(() => accepted?.id).toBeTruthy();
  await expect(page.getByRole('alert')).toContainText('提交暂未确认'); await page.reload();
  await expect(page.getByRole('button', { name: '重试同一条消息' })).toBeVisible(); await expect(page.getByLabel('模型', { exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '重试同一条消息' }).click(); await expect(page).toHaveURL(new RegExp(`/app/chat/${accepted!.id}$`));
  const snapshot = await (await page.request.get(`/api/v1/conversations/${accepted!.id}/snapshot`)).json(); expect(snapshot.tasks).toHaveLength(1);
});
test('owner credential lifecycle never echoes secret or stores it in browser state', async ({ page }) => {
  await login(page); await page.getByText('模型密钥', { exact: true }).click();
  const sentinel = 'R13_USER_KEY_SENTINEL_NOT_A_REAL_KEY';
  await page.getByLabel('新的 DeepSeek API key', { exact: true }).fill(sentinel); await page.getByRole('button', { name: '保存或替换个人密钥' }).click();
  await expect(page.getByRole('status')).toContainText('已加密保存'); await expect(page.getByLabel('新的 DeepSeek API key', { exact: true })).toHaveValue('');
  const meta = await page.request.get('/api/v1/me/model-credentials'); expect(await meta.text()).not.toContain(sentinel);
  expect(await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } })) ).not.toContain(sentinel);
  await page.getByLabel('凭据来源', { exact: true }).selectOption('USER'); await page.getByLabel('模型', { exact: true }).selectOption({ label: 'deepseek-v4-pro' });
  await page.getByLabel('消息', { exact: true }).fill('fixture:normal'); await page.getByRole('button', { name: '发送', exact: true }).click(); await expect(page.getByRole('status').first()).toContainText('已完成');
  await page.getByRole('button', { name: '撤销个人密钥' }).click(); await expect(page.getByRole('status').last()).toContainText('已撤销');
  await page.getByLabel('消息', { exact: true }).fill('fixture:normal'); await page.getByRole('button', { name: '发送', exact: true }).click(); await expect(page.getByRole('alert')).toContainText('所选凭据未配置或已撤销');
  await page.getByLabel('凭据来源', { exact: true }).selectOption('PLATFORM');
});

test('preference preconditions, CSRF and cross-space credential access are enforced by real API', async ({ browser, baseURL }) => {
  const a = await browser.newContext({ baseURL }); const b = await browser.newContext({ baseURL });
  try {
    await login(await a.newPage(), 'alice'); await login(await b.newPage(), 'bob');
    const original = await (await a.request.get('/api/v1/me/preferences')).json(); const csrf = await (await a.request.get('/api/v1/auth/csrf')).json();
    const models = await (await a.request.get('/api/v1/models')).json(); const flash = models.items.find((item: { name: string }) => item.name === 'deepseek-flash');
    const headers = { Origin: baseURL!, 'X-CSRF-Token': csrf.csrfToken, 'If-Match': `"${original.version}"` };
    const saved = await a.request.patch('/api/v1/me/preferences', { headers, data: { defaultModelId: flash.id } }); expect(saved.status()).toBe(200);
    expect((await a.request.patch('/api/v1/me/preferences', { headers, data: { defaultCredentialSource: 'USER' } })).status()).toBe(412);
    expect((await a.request.patch('/api/v1/me/preferences', { headers: { Origin: baseURL! }, data: { defaultModelId: flash.id } })).status()).toBe(403);
    const next = await saved.json(); expect((await a.request.patch('/api/v1/me/preferences', { headers: { ...headers, 'If-Match': `"${next.version}"`, Origin: 'https://untrusted.example' }, data: {} })).status()).toBe(403);
    const bob = await (await b.request.get('/api/v1/me')).json(); const foreign = { 'X-Space-Id': bob.spaces[0].id };
    expect((await a.request.get('/api/v1/me/model-credentials', { headers: foreign })).status()).toBe(404);
    expect((await a.request.get('/api/v1/effective-config', { headers: foreign })).status()).toBe(404);
    expect((await a.request.put('/api/v1/me/model-credentials/deepseek', { headers: { ...headers, 'If-Match': '"0"' }, data: { apiKey: 'fixture-only', scope: 'PLATFORM' } })).status()).toBe(400);
    expect((await a.request.patch('/api/v1/me/preferences', { headers: { ...headers, 'If-Match': `"${next.version}"` }, data: { defaultModelId: original.defaultModelId, defaultCredentialSource: original.defaultCredentialSource } })).status()).toBe(200);
  } finally { await a.close(); await b.close(); }
});
