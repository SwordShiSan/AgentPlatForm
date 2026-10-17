import { test, expect } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

test.skip(process.env.R1_CLOUD_ACCEPTANCE !== '1', 'Explicit promoted HTTP cloud candidate required');
test.use({ trace: 'off', screenshot: 'off' });

test('HTTP cloud identity, one real stream, refresh, admin isolation and public boundary', async ({ browser, baseURL }) => {
  test.setTimeout(180_000); expect(baseURL).toBe('http://124.222.55.140');
  const folder = process.env.R1_PRIVATE_ACCEPTANCE_ROOT!;
  const accounts = JSON.parse(readFileSync(resolve(folder, 'accounts.json'), 'utf8')).accounts as { username: string; password: string }[];
  const ordinary = accounts.find(a => a.username === 'r1acceptance')!;
  const administrator = accounts.find(a => a.username === 'platformadmin')!;
  const user = await browser.newContext({ baseURL }); const admin = await browser.newContext({ baseURL });
  try {
    for (const [context, account] of [[user, ordinary], [admin, administrator]] as const) {
      const page = await context.newPage(); await page.goto('/login');
      await page.getByLabel('账号', { exact: true }).fill(account.username);
      await page.getByLabel('密码', { exact: true }).fill(account.password);
      await page.getByRole('button', { name: '登录', exact: true }).click();
      await expect(page.getByRole('link', { name: '普通聊天', exact: true })).toBeVisible();
      const cookie = (await context.cookies()).find(c => c.name === 'ap_session')!;
      expect(cookie.httpOnly).toBe(true); expect(cookie.sameSite).toBe('Lax'); expect(cookie.secure).toBe(false);
    }
    const page = user.pages()[0]; await page.getByRole('link', { name: '普通聊天', exact: true }).click();
    expect(await page.evaluate(() => globalThis.isSecureContext)).toBe(false);
    await expect(page.getByLabel('模型', { exact: true })).toHaveValue(/.+/);
    await page.getByLabel('模型', { exact: true }).selectOption({ label: 'deepseek-flash' });
    await page.getByLabel('凭据来源', { exact: true }).selectOption('PLATFORM');
    await page.getByLabel('消息', { exact: true }).fill('只回复：云端验收通过');
    await page.getByLabel('消息', { exact: true }).press('Control+Enter');
    await expect(page).toHaveURL(/\/app\/chat\/[a-f0-9-]+$/);
    await expect(page.getByRole('status').first()).toContainText(/已完成|失败|需要处理/, { timeout: 125_000 });
    await expect(page.getByRole('status').first()).toContainText('已完成');
    const conversation = page.url().split('/').at(-1)!;
    const stream = await page.evaluate(async id => {
      const start = performance.now(); const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 8000);
      try {
        const response = await fetch(`/api/v1/conversations/${id}/events?after=0`, { signal: controller.signal });
        const first = await response.body!.getReader().read();
        return { status: response.status, contentType: response.headers.get('Content-Type'), first: new TextDecoder().decode(first.value), elapsed: performance.now() - start };
      } finally { clearTimeout(timer); controller.abort(); }
    }, conversation);
    expect(stream.status).toBe(200); expect(stream.contentType).toContain('text/event-stream');
    expect(stream.first).toContain(': connected'); expect(stream.elapsed).toBeLessThan(8000);
    const snapshot = await (await user.request.get(`/api/v1/conversations/${conversation}/snapshot`)).json();
    const task = snapshot.tasks.at(-1).taskId;
    const diagnostic = await (await user.request.get(`/api/v1/tasks/${task}/diagnostics`)).json();
    expect(diagnostic.code).toBeNull();
    for (const route of [`/conversations/${conversation}`, `/conversations/${conversation}/snapshot`, `/conversations/${conversation}/events`, `/tasks/${task}`, `/tasks/${task}/configuration`, `/tasks/${task}/usage`, `/tasks/${task}/diagnostics`]) {
      expect((await admin.request.get('/api/v1' + route)).status()).toBe(404);
    }
    for (const path of ['/api/v1/internal/metrics', '/api/v1/actuator/env', '/api/v1/actuator/metrics']) {
      const response = await user.request.get(path); expect(response.status()).toBe(404);
      expect(await response.text()).not.toContain('jdbc:postgresql');
    }
    const health = await user.request.get('/api/v1/health'); expect(await health.json()).toEqual({ status: 'UP' });
    const readiness = await user.request.get('/api/v1/readiness'); expect(await readiness.json()).toEqual({ status: 'READY' });
    const host = await user.request.get('/api/v1/readiness', { headers: { Host: 'agentplatform.lailihao.cloud' } }); expect(host.status()).toBe(200);
    await page.reload(); await expect(page.locator('.chat-message.assistant')).toHaveCount(1);
    for (const width of [375, 768, 1440]) {
      await page.setViewportSize({ width, height: 900 }); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    }
    await page.getByLabel('主题', { exact: true }).selectOption('dark'); await page.getByLabel('主题', { exact: true }).selectOption('light');
    await page.evaluate(() => { document.documentElement.style.zoom = '2'; }); await expect(page.getByLabel('消息', { exact: true })).toBeVisible();
    const csrf = (await (await user.request.get('/api/v1/auth/csrf')).json()).csrfToken;
    expect((await user.request.post('/api/v1/auth/logout', { headers: { Origin: baseURL!, 'X-CSRF-Token': csrf } })).status()).toBe(204);
    await page.reload(); await expect(page).toHaveURL(/\/login$/);
    writeFileSync(resolve(folder, 'cloud-browser.json'), JSON.stringify({ status: 'PASS', taskId: task, conversationId: conversation, realTurns: 1, httpCookieVerified: true, adminContentBypass: false, domainHostRouting: true, dnsChecked: false }, null, 2));
  } finally { await user.close(); await admin.close(); }
});
