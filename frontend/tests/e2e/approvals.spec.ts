import { test, expect, type Page } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

test.skip(process.env.R43_BROWSER_ACCEPTANCE !== '1', 'Requires isolated R4.3 API/PG/MQ and deterministic approval provider');
test.use({ trace: 'off', screenshot: 'off' });
test.setTimeout(120_000);
const fixture = resolve(import.meta.dirname, '../../../.cache/r43-local/fixture');
type Snapshot = { tasks: { taskId: string; status: string }[]; pending: { interactionId: string; taskId: string; version: number;
  kind: string; approval?: { operationId: string; callHash: string; stale: boolean; allowedActions: string[] } }[] };

async function login(page: Page, admin = false) {
  await page.goto('/'); await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel('账号', { exact: true }).fill(admin ? 'r43_admin' : 'r43_user');
  await page.getByLabel('密码', { exact: true }).fill(readFileSync(resolve(fixture, admin ? 'alice-password' : 'bob-password'), 'utf8').trim());
  const response = page.waitForResponse(value => value.url().endsWith('/api/v1/auth/login') && value.request().method() === 'POST', { timeout: 30_000 });
  await page.getByRole('button', { name: '登录', exact: true }).click(); expect((await response).status()).toBe(200);
  await expect(page).toHaveURL(/\/app\/chat$/);
}
async function snapshot(page: Page): Promise<Snapshot> {
  const id = page.url().split('/').at(-1)!;
  const response = await page.request.get(`/api/v1/conversations/${id}/snapshot`); expect(response.ok()).toBe(true);
  return response.json();
}
async function begin(page: Page, prompt = 'fixture:approval') {
  await page.getByRole('button', { name: '新建对话', exact: true }).click();
  await page.getByLabel('先制定计划，再确认文本执行').uncheck();
  const workspaces = await (await page.request.get('/api/v1/workspaces?limit=100')).json();
  const workspace = workspaces.items.find((value: { state: string }) => value.state === 'READY');
  expect(workspace).toBeTruthy();
  await page.getByRole('combobox', { name: '本次工作区', exact: true }).selectOption(workspace.id);
  await page.getByRole('combobox', { name: '本次权限', exact: true }).selectOption('RISK_BASED');
  await page.getByLabel('消息', { exact: true }).fill(prompt);
  await page.getByLabel('消息', { exact: true }).press('Control+Enter');
  await expect(page).toHaveURL(/\/app\/chat\/[0-9a-f-]+$/);
  await expect.poll(async () => (await snapshot(page)).tasks.at(-1)?.status, { timeout: 60_000 }).toBe('WAITING_INPUT');
  const card = page.getByTestId('approval-card'); await expect(card).toBeVisible();
  await expect(card.getByRole('button', { name: '允许这一次', exact: true })).toBeEnabled();
  const state = await snapshot(page); const pending = state.pending.find(value => value.kind === 'TOOL_APPROVAL');
  expect(pending?.approval?.operationId).toBeTruthy(); expect(pending?.approval?.callHash).toMatch(/^[a-f0-9]{64}$/);
  return { task: state.tasks.at(-1)!.taskId, pending: pending! };
}
async function headers(page: Page) {
  const token = (await (await page.request.get('/api/v1/auth/csrf')).json()).csrfToken;
  return { Origin: 'http://127.0.0.1:18103', 'X-CSRF-Token': token };
}
async function terminal(page: Page, status = 'SUCCEEDED') {
  await expect.poll(async () => (await snapshot(page)).tasks.at(-1)?.status, { timeout: 60_000 }).toBe(status);
  await expect(page.getByTestId('approval-card')).toHaveCount(0);
}
async function stalePermissions(page: Page, task: string) {
  const permission = await (await page.request.get(`/api/v1/tasks/${task}/permissions`)).json();
  const response = await page.request.patch(`/api/v1/tasks/${task}/permissions`, { headers: {
    ...await headers(page), 'Idempotency-Key': crypto.randomUUID(), 'If-Match': `"${permission.version}"`,
  }, data: { mode: permission.mode === 'FULL' ? 'RISK_BASED' : 'FULL' } });
  expect(response.status()).toBe(200); await page.reload();
  await expect(page.getByTestId('approval-card')).toContainText('操作或授权版本已改变');
  const pending = (await snapshot(page)).pending.find(value => value.kind === 'TOOL_APPROVAL');
  expect(pending?.approval?.stale).toBe(true); expect(pending?.approval?.allowedActions).toEqual(['DENY']);
}

test('once approval survives refresh and lost response without replacing the original operation', async ({ page, baseURL }) => {
  expect(baseURL).toBe('http://127.0.0.1:18103'); await login(page); const { task, pending } = await begin(page);
  await page.reload(); const card = page.getByTestId('approval-card'); await expect(card).toContainText('不修改参数');
  expect((await snapshot(page)).pending.find(value => value.kind === 'TOOL_APPROVAL')?.approval?.operationId).toBe(pending.approval!.operationId);
  const ui = resolve(import.meta.dirname, '../../../.cache/r43-local/ui'); mkdirSync(ui, { recursive: true });
  for (const width of [375, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 }); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    await expect(card.getByRole('button', { name: '允许这一次', exact: true })).toBeVisible();
    await page.screenshot({ path: resolve(ui, `approval-${width}.png`), fullPage: true });
  }
  for (const theme of ['light', 'dark']) { await page.getByLabel('主题', { exact: true }).selectOption(theme); await page.screenshot({ path: resolve(ui, `approval-${theme}.png`), fullPage: true }); }
  await page.evaluate(() => { document.documentElement.style.zoom = '2'; });
  await expect(card.getByRole('button', { name: '允许这一次', exact: true })).toBeVisible();
  await page.evaluate(() => { document.documentElement.style.zoom = ''; });
  let submitted: { key: string; body: unknown } | undefined;
  await page.route(`**/api/v1/tasks/${task}/interactions/${pending.interactionId}/answer`, async route => {
    submitted = { key: route.request().headers()['idempotency-key']!, body: route.request().postDataJSON() };
    const response = await route.fetch(); expect(response.status()).toBe(202); await route.abort('failed');
  });
  await card.getByRole('button', { name: '允许这一次', exact: true }).focus(); await page.keyboard.press('Enter');
  await terminal(page); await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('666');
  expect(submitted).toBeTruthy(); expect(submitted!.body).toEqual({ expectedVersion: pending.version, decision: 'ALLOW_ONCE', optionIds: [] });
  const replay = await page.request.post(`/api/v1/tasks/${task}/interactions/${pending.interactionId}/answer`, {
    headers: { ...await headers(page), 'Idempotency-Key': submitted!.key }, data: submitted!.body,
  }); expect(replay.status()).toBe(202);
  await page.reload(); await terminal(page); expect((await snapshot(page)).tasks.at(-1)?.taskId).toBe(task);
});

test('denying the original call does not loop a repeated denied fingerprint', async ({ page }) => {
  await login(page); const { task } = await begin(page, 'fixture:approval-repeat-denied');
  const card = page.getByTestId('approval-card'); await card.getByRole('button', { name: '拒绝本次操作', exact: true }).click();
  await terminal(page); await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('拒绝');
  await page.reload(); await terminal(page); expect((await snapshot(page)).tasks.at(-1)?.taskId).toBe(task);
});

test('two actual tabs cannot accept conflicting decisions and admin cannot read the other user approval', async ({ page, browser }) => {
  await login(page); const { task, pending } = await begin(page); const conversation = page.url().split('/').at(-1)!;
  const otherTab = await page.context().newPage(); await otherTab.route('**/api/v1/conversations/*/events?*', route => route.abort());
  await otherTab.goto(page.url()); await expect(otherTab.getByTestId('approval-card')).toBeVisible();
  const approved = page.waitForResponse(value => value.url().endsWith(`/interactions/${pending.interactionId}/answer`) && value.request().method() === 'POST');
  await page.getByTestId('approval-card').getByRole('button', { name: '允许这一次', exact: true }).click(); expect((await approved).status()).toBe(202);
  const conflict = otherTab.waitForResponse(value => value.url().endsWith(`/interactions/${pending.interactionId}/answer`) && value.request().method() === 'POST');
  await otherTab.getByTestId('approval-card').getByRole('button', { name: '拒绝本次操作', exact: true }).click(); expect((await conflict).status()).toBe(409);
  await terminal(page); await otherTab.reload(); await terminal(otherTab); await otherTab.close();
  const context = await browser.newContext(); const admin = await context.newPage(); await login(admin, true);
  expect((await admin.request.get(`/api/v1/tasks/${task}`)).status()).toBe(404);
  expect((await admin.request.get(`/api/v1/conversations/${conversation}/snapshot`)).status()).toBe(404);
  const denied = await admin.request.post(`/api/v1/tasks/${task}/interactions/${pending.interactionId}/answer`, {
    headers: { ...await headers(admin), 'Idempotency-Key': crypto.randomUUID() }, data: { expectedVersion: pending.version, decision: 'ALLOW_ONCE' },
  }); expect(denied.status()).toBe(404); await context.close();
});

test('stale approvals only permit deny or task cancellation', async ({ page }) => {
  await login(page); const first = await begin(page); await stalePermissions(page, first.task);
  const card = page.getByTestId('approval-card'); await expect(card.getByRole('button', { name: '允许这一次', exact: true })).toHaveCount(0);
  await card.getByRole('button', { name: '拒绝本次操作', exact: true }).click(); await terminal(page);
  const second = await begin(page); await stalePermissions(page, second.task);
  await expect(page.getByTestId('approval-card').getByRole('button', { name: '允许这一次', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '取消回复', exact: true }).click(); await terminal(page, 'CANCELLED');
});

test('ordinary Plan and Question still resume the same unbound text task after approval integration', async ({ page }) => {
  await login(page); await page.getByRole('button', { name: '新建对话', exact: true }).click();
  await page.getByRole('combobox', { name: '本次工作区', exact: true }).selectOption('');
  await page.getByRole('combobox', { name: '本次权限', exact: true }).selectOption('RISK_BASED');
  await page.getByLabel('先制定计划，再确认文本执行').check();
  await page.getByLabel('消息', { exact: true }).fill('fixture:plan-question 澄清后仅交付文本方案');
  await page.getByLabel('消息', { exact: true }).press('Control+Enter');
  await expect(page).toHaveURL(/\/app\/chat\/[0-9a-f-]+$/);
  await expect.poll(async () => (await snapshot(page)).tasks.at(-1)?.status, { timeout: 60_000 }).toBe('WAITING_INPUT');
  const task = (await snapshot(page)).tasks.at(-1)!.taskId;
  const question = page.getByTestId('question-card'); await expect(question).toBeVisible();
  await expect(page.getByTestId('approval-card')).toHaveCount(0);
  await question.getByLabel('简洁', { exact: true }).check();
  await question.getByLabel('补充说明', { exact: true }).fill('保留中文与emoji😀');
  await question.getByRole('button', { name: '提交回答', exact: true }).click();
  const plan = page.getByTestId('plan-card'); await expect(plan).toBeVisible(); await page.reload();
  await expect(plan).toBeVisible(); await expect(plan).toContainText('不授予文件、终端或其他工具权限');
  expect((await snapshot(page)).tasks.at(-1)?.taskId).toBe(task);
  const permission = await (await page.request.get(`/api/v1/tasks/${task}/permissions`)).json(); expect(permission.workspaceId).toBeNull();
  await plan.getByRole('button', { name: '仅交付方案', exact: true }).click();
  await terminal(page); await expect(plan).toHaveCount(0); await expect(question).toHaveCount(0);
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('明确用户提供的文本需求');
  await page.reload(); await terminal(page); expect((await snapshot(page)).tasks.at(-1)?.taskId).toBe(task);
});
