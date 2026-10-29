import { test, expect, type Page } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
const root = resolve(process.cwd(), '../.cache/r51-local');
const selected = process.env.R51_NATIVE === '1';
test.use({ trace: 'off' });
test.skip(!selected, 'Explicit independent native fixture only; not a production test');
const privateJson = (file: string) => JSON.parse(readFileSync(resolve(root, file), 'utf8'));
async function login(page: Page) {
  await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill('r51_admin');
  await page.getByLabel('密码', { exact: true }).fill(readFileSync(resolve(root, 'native/accounts/r51_admin.password'), 'utf8'));
  await page.getByLabel('密码', { exact: true }).press('Enter'); await expect(page).not.toHaveURL(/\/login$/);
}
async function open(page: Page) {
  const ids = privateJson('native-file-identities.json'); await page.goto(`/app/workspaces/${ids.workspaceId}`);
  await page.locator('.file-list li').filter({ hasText: 'editor.txt' }).getByRole('button', { name: '编辑文本', exact: true }).click();
  await expect(page.getByRole('textbox', { name: '文件文本', exact: true })).toBeVisible();
}
async function saved(page: Page) { await expect(page.getByText('保存已完成，版本已封存。', { exact: true })).toBeVisible({ timeout: 90000 }); }
test('real API edit/conflict/explicit merge/save-as/response loss and session isolation', async ({ browser }) => {
  test.setTimeout(300000);
  const a = await browser.newContext({ viewport: { width: 375, height: 900 } });
  const b = await browser.newContext({ viewport: { width: 375, height: 900 } });
  const first = await a.newPage(), second = await b.newPage();
  first.on('dialog', d => d.accept()); second.on('dialog', d => d.accept());
  const errors: string[] = []; first.on('pageerror', error => errors.push(error.message)); second.on('pageerror', error => errors.push(error.message));
  try {
    await login(first); await login(second); await open(first); await open(second);
    await first.getByRole('textbox', { name: '文件文本', exact: true }).fill('first native 中文😀\n');
    await first.getByRole('button', { name: '保存', exact: true }).click(); await saved(first);
    await second.getByRole('textbox', { name: '文件文本', exact: true }).fill('second preserved local\n');
    // A late click may receive412 directly or the committed version poll may pre-detect it.
    if (await second.getByRole('button', { name: '保存', exact: true }).isEnabled()) await second.getByRole('button', { name: '保存', exact: true }).click();
    await expect(second.getByRole('button', { name: '读取最新版本并比较' })).toBeVisible({ timeout: 15000 });
    await expect(second.getByRole('textbox', { name: '文件文本', exact: true })).toHaveValue('second preserved local\n');
    await second.getByRole('button', { name: '读取最新版本并比较' }).click();
    await expect(second.getByRole('button', { name: '采用最新基线，手动合并' })).toBeEnabled();
    await second.getByRole('button', { name: '采用最新基线，手动合并' }).click();
    await second.getByRole('textbox', { name: '文件文本', exact: true }).fill('merged explicit native\n');
    await second.getByRole('button', { name: '保存', exact: true }).click(); await saved(second);
    // Lose only the accepted completion response. Poll the original operation; never create another upload.
    let completionLost = false, uploads = 0;
    await second.route('**/api/v1/workspaces/*/uploads', async route => { if (route.request().method() === 'POST') uploads++; await route.continue(); });
    await second.route('**/api/v1/uploads/*/complete', async route => {
      if (!completionLost) { completionLost = true; await route.fetch(); await route.abort('failed'); } else await route.continue();
    });
    await second.getByRole('textbox', { name: '文件文本', exact: true }).fill('response-loss recovered once\n');
    await second.getByRole('button', { name: '保存', exact: true }).click(); await saved(second); expect(uploads).toBe(1);
    await second.unrouteAll({ behavior: 'wait' });
    await second.getByRole('textbox', { name: '文件文本', exact: true }).fill('save-as native version\n');
    await second.getByLabel('另存相对路径').fill('editor-copy.txt');
    await second.getByRole('button', { name: '另存新文件', exact: true }).click(); await saved(second);
    const files = await (await b.request.get(`/api/v1/workspaces/${privateJson('native-file-identities.json').workspaceId}/files`)).json();
    const copy = files.items.find((f: { path: string }) => f.path === 'editor-copy.txt'); expect(copy).toBeTruthy();
    const body = await (await b.request.get(`/api/v1/files/${copy.id}/content?versionId=${copy.versionId}`)).body();
    expect(body.toString('utf8')).toBe('save-as native version\r\n'); expect(createHash('sha256').update(body).digest('hex')).toBe(copy.sha256);
    await second.getByRole('textbox', { name: '文件文本', exact: true }).fill('refresh retained local\n'); await second.waitForTimeout(300);
    await second.reload(); await open(second); await expect(second.getByRole('textbox', { name: '文件文本', exact: true })).toHaveValue('refresh retained local\n');
    for (const width of [375, 768, 1440]) {
      await second.setViewportSize({ width, height: 900 });
      for (const theme of ['light', 'dark']) { await second.getByLabel('主题', { exact: true }).selectOption(theme); await expect(second.locator('html')).toHaveAttribute('data-theme', theme); }
      await second.evaluate(() => { document.body.style.zoom = '2'; });
      await expect(second.getByRole('button', { name: '关闭编辑', exact: true })).toBeVisible();
      await second.evaluate(() => { document.body.style.zoom = '1'; });
    }
    await second.goto('/app/settings/account'); await second.getByRole('button', { name: '退出登录', exact: true }).click();
    await expect(second).toHaveURL(/\/login$/);
    expect(await second.evaluate(() => Object.keys(sessionStorage).filter(k => k.startsWith('agentplatform:editor:')).length)).toBe(0);
    expect(errors).toEqual([]);
    writeFileSync(resolve(root, 'native-browser-result.json'), JSON.stringify({ status: 'PASS', supplierCalls: 0, scope: 'actual PG/MQ/XFS/runner/S3 API editor, explicit conflicts/merge, response loss original operation, save-as hash, session drafts/logout,375/768/1440,200%,themes', uploadsForResponseLoss: uploads }, null, 2));
  } finally { await a.close(); await b.close(); }
});
