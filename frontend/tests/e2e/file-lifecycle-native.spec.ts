import { test, expect } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
const root = resolve(process.cwd(), '../.cache/r52-local');
test.use({ trace: 'off', screenshot: 'off' });
test.skip(process.env.R52_NATIVE !== '1', 'Explicit isolated R52 native fixture only');
test('actual lifecycle UI preserves identities, immutable history and original requests', async ({ browser }) => {
  test.setTimeout(180000);
  const context = await browser.newContext({ baseURL: process.env.BASE_URL, viewport: { width: 375, height: 900 } });
  const page = await context.newPage(); const errors: string[] = []; page.on('pageerror', e => errors.push(e.message));
  try {
    const ids = JSON.parse(readFileSync(resolve(root, 'native-file-identities.json'), 'utf8'));
    await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill('r52_admin');
    await page.getByLabel('密码', { exact: true }).fill(readFileSync(resolve(root, 'native/accounts/r52_admin.password'), 'utf8'));
    await page.getByLabel('密码', { exact: true }).press('Enter'); await expect(page).not.toHaveURL(/\/login$/);
    const original = await (await context.request.get(`/api/v1/files/${ids.browser.id}`)).json();
    const movedName = `browser-ui-${Date.now()}.txt`;
    await page.goto(`/app/workspaces/${ids.workspaceId}`);
    const row = (name: string) => page.locator('.file-list > li').filter({ has: page.locator('strong').filter({ hasText: name }) });
    await row(original.name).getByRole('button', { name: '历史版本', exact: true }).click();
    await page.getByRole('button', { name: '只读查看', exact: true }).first().click();
    await expect(page.getByLabel('历史版本只读内容')).toContainText('browser original');
    await page.getByRole('button', { name: '关闭面板', exact: true }).click();
    await row(original.name).getByRole('button', { name: '移动／重命名', exact: true }).click();
    if (await page.getByRole('button', { name: '完成并关闭', exact: true }).isVisible()) {
      await page.getByRole('button', { name: '完成并关闭', exact: true }).click();
      await row(original.name).getByRole('button', { name: '移动／重命名', exact: true }).click();
    }
    await page.getByLabel('名称', { exact: true }).fill(movedName);
    let submitted = 0;
    await page.route('**/api/v1/files/*/move', async route => {
      if (route.request().method() === 'POST') {
        const response = await route.fetch();
        if (response.status() === 202) { submitted++; await route.abort('failed'); }
        else await route.fulfill({ response });
      }
      else await route.continue();
    });
    for (let admission = 0; admission < 3; admission++) {
      await page.getByRole('button', { name: '确认移动／重命名', exact: true }).click();
      const rejected = page.getByRole('button', { name: '保留输入并读取当前文件状态', exact: true });
      await expect.poll(async () => await page.getByText('操作已完成，版本记录已保存。', { exact: true }).isVisible() || await rejected.isVisible()).toBe(true);
      if (!await rejected.isVisible()) break;
      await rejected.click();
    }
    await expect(page.getByText('操作已完成，版本记录已保存。', { exact: true })).toBeVisible({ timeout: 60000 });
    expect(submitted).toBe(1); await page.unrouteAll({ behavior: 'wait' });
    await page.getByRole('button', { name: '完成并关闭', exact: true }).click();
    await page.reload(); await expect(row(movedName)).toBeVisible();
    await row(movedName).getByRole('button', { name: '删除工作副本', exact: true }).click();
    await expect(page.getByText('此次影响 1 项（含所选文件或目录）。', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '确认删除工作副本', exact: true }).click();
    await expect(page.getByText('操作已完成，版本记录已保存。', { exact: true })).toBeVisible({ timeout: 60000 });
    await page.getByRole('button', { name: '完成并关闭', exact: true }).click();
    await page.getByRole('button', { name: '已删除文件', exact: true }).click();
    await page.getByRole('button', { name: '查看／恢复', exact: true }).filter({ visible: true }).first().click();
    await page.getByLabel('名称', { exact: true }).fill('browser-restored.txt');
    await page.getByRole('button', { name: '确认恢复为新版本', exact: true }).click();
    await expect(page.getByText('操作已完成，版本记录已保存。', { exact: true })).toBeVisible({ timeout: 60000 });
    await page.getByRole('button', { name: '完成并关闭', exact: true }).click();
    await expect(row('browser-restored.txt')).toBeVisible();
    const current = await (await context.request.get(`/api/v1/files/${ids.browser.id}`)).json(); expect(current.path).toBe('browser-restored.txt');
    expect(current.versionId).not.toBe(ids.browser.versionId);
    const content = await (await context.request.get(`/api/v1/files/${ids.browser.id}/content?versionId=${ids.browser.versionId}`)).text(); expect(content).toBe('browser original\n');
    await row('browser-restored.txt').getByRole('button', { name: '编辑文本', exact: true }).click();
    await page.getByRole('textbox', { name: '文件文本', exact: true }).fill('retained unsaved after external move\n');
    const csrf = await (await context.request.get('/api/v1/auth/csrf')).json();
    const external = await context.request.post(`/api/v1/files/${ids.browser.id}/move`, { data: { name: 'browser-external.txt', targetParentId: null },
      headers: { Origin: process.env.BASE_URL!, 'X-CSRF-Token': csrf.csrfToken, 'X-Space-Id': ids.spaceId, 'If-Match': `"${current.revision}"`, 'Idempotency-Key': randomUUID() } });
    expect(external.status()).toBe(202); const externalOp = await external.json();
    await expect.poll(async () => (await (await context.request.get(`/api/v1/file-operations/${externalOp.id}`)).json()).state).toBe('COMMITTED');
    await expect(page.getByRole('textbox', { name: '文件文本', exact: true })).toHaveValue('retained unsaved after external move\n');
    await expect(page.getByRole('heading', { name: /browser-external.txt.*未保存/ })).toBeVisible();
    await page.getByRole('button', { name: '读取最新版本并比较', exact: true }).click();
    await page.getByRole('button', { name: '采用最新基线，手动合并', exact: true }).click();
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.getByText('保存已完成，版本已封存。', { exact: true })).toBeVisible({ timeout: 60000 });
    await page.getByRole('button', { name: '关闭编辑', exact: true }).click();
    for (const width of [375, 768, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      for (const theme of ['light', 'dark']) { await page.getByLabel('主题', { exact: true }).selectOption(theme); await expect(page.locator('html')).toHaveAttribute('data-theme', theme); }
      await page.evaluate(() => { document.body.style.zoom = '2'; }); await expect(row('browser-external.txt')).toBeVisible();
      await page.keyboard.press('Tab'); await page.evaluate(() => { document.body.style.zoom = '1'; });
    }
    expect(errors).toEqual([]);
    writeFileSync(resolve(root, 'native-browser-result.json'), JSON.stringify({ status: 'PASS', supplierCalls: 0, scope: 'actual API lifecycle/history/read-only preview/delete/restore/original response-loss query,375/768/1440,200%,keyboard/themes', movePosts: submitted }, null, 2));
  } finally { await context.close(); }
});
