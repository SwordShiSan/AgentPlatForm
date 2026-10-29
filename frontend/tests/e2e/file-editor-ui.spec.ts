import { test, expect } from '@playwright/test';

// Browser contract fixture only; physical persistence is a separate real-dependency gate.
for (const width of [375, 768, 1440]) test(`file editor draft and local worker at ${width}`, async ({ page }) => {
  await page.setViewportSize({ width, height: 900 });
  const id = '0199e000-0000-7000-8000-000000000051';
  const item = { id, workspaceId: id, path: 'draft.txt', parent: '', name: 'draft.txt', kind: 'FILE', versionId: id, revision: 1, collectionState: 'CONFIRMED', bytes: 5, sha256: 'a'.repeat(64) };
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/content')) return route.fulfill({ body: 'hello', contentType: 'application/octet-stream' });
    const body = path.endsWith('/me') ? { id, username: 'r51_admin', spaces: [{ id, type: 'PERSONAL' }] }
      : path.endsWith('/storage-usage') ? { committedBytes: 5, reservedBytes: 0, objectLimitBytes: 10737418240, workspaceQuotaBytes: 2147483648, workspaceQuotaInodes: 100000, measuredBytes: null }
      : path.endsWith('/files') ? { items: [item], nextCursor: null }
      : path.includes('/files/') ? item
      : { id, name: 'R51 fixture', state: 'READY', quotaInodes: 100000 };
    await route.fulfill({ json: body });
  });
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  await page.goto(`/app/workspaces/${id}`);
  await page.getByRole('button', { name: '编辑文本', exact: true }).click();
  await expect(page.getByRole('region', { name: '文件文本编辑' })).toBeVisible();
  if (width < 768) {
    await page.getByRole('textbox', { name: '文件文本', exact: true }).fill('中文😀 local draft');
  } else {
    await expect(page.locator('.monaco-editor').first()).toBeVisible();
    await page.locator('.monaco-editor').first().click();
    await page.keyboard.press('Control+A'); await page.keyboard.type('local draft');
  }
  await expect(page.getByRole('heading', { name: /未保存/ })).toBeVisible();
  await expect.poll(() => page.evaluate(() => Object.keys(sessionStorage).filter(k => k.startsWith('agentplatform:editor:')).length)).toBe(1);
  page.on('dialog', dialog => dialog.accept()); await page.reload();
  await page.getByRole('button', { name: '编辑文本', exact: true }).click();
  await expect(page.getByText('已恢复当前浏览器会话草稿；未自动保存。')).toBeVisible();
  expect(errors).toEqual([]);
});
