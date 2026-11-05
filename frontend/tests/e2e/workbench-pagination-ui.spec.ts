import { test, expect } from '@playwright/test';

test.use({ proxy: { server: 'http://127.0.0.1:9', bypass: '*' }, trace: 'off', screenshot: 'off' });

// Contract/performance fixture only: ten thousand logical entries, no physical files.
test('ten thousand logical nodes remain paginated and the directory tree is virtualized', async ({ page }) => {
  const id = '0199e000-0000-7000-8000-000000000054';
  const reads: Array<{ parent: string; limit: number; cursor: string | null }> = [];
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url()); const path = url.pathname;
    if (path.endsWith('/files')) {
      const parent = url.searchParams.get('parent') ?? ''; const limit = Number(url.searchParams.get('limit') ?? 100);
      const cursor = url.searchParams.get('cursor'); reads.push({ parent, limit, cursor });
      expect(limit).toBeLessThanOrEqual(100);
      const begin = Number(cursor ?? 0);
      const items = Array.from({ length: Math.min(limit, 10_000 - begin) }, (_, index) => {
        const number = String(begin + index).padStart(5, '0'); const name = `${parent ? 'file' : 'directory'}-${number}`;
        return { id: `${id.slice(0, 24)}${String(begin + index + (parent ? 20_000 : 1)).padStart(12, '0')}`, workspaceId: id,
          name, path: parent ? `${parent}/${name}` : name, parent, kind: parent ? 'FILE' : 'DIRECTORY', versionId: parent ? id : null,
          revision: 1, collectionState: 'CONFIRMED', bytes: parent ? 0 : null, sha256: parent ? 'a'.repeat(64) : null };
      });
      return route.fulfill({ json: { items, nextCursor: begin + items.length < 10_000 ? String(begin + items.length) : null } });
    }
    const data = path.endsWith('/me') ? { id, username: 'fixture-admin', spaces: [{ id, type: 'PERSONAL' }] }
      : path.endsWith('/storage-usage') ? { objectLimitBytes: 10737418240, committedBytes: 0, reservedBytes: 0, workspaceQuotaBytes: 2147483648, workspaceQuotaInodes: 100000 }
      : path.endsWith('/environment') ? { state: 'NOT_CREATED', generation: 0, version: 0, allowedActions: ['PREPARE'] }
      : path.endsWith('/deleted-files') ? { items: [], nextCursor: null }
      : { id, name: 'Paged workspace', state: 'READY', version: 1, quotaBytes: 2147483648, quotaInodes: 100000 };
    return route.fulfill({ json: data });
  });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`/app/workspaces/${id}`);
  const tree = page.getByRole('region', { name: '文件目录树' });
  await expect(tree.getByText('directory-00000', { exact: true })).toBeVisible();
  expect(await tree.locator('.n-tree-node').count()).toBeLessThan(80);
  expect(reads.length).toBeLessThanOrEqual(2); expect(reads.every(read => read.cursor === null)).toBe(true);
  await tree.locator('.n-tree-node-switcher').first().click();
  await expect.poll(() => reads.filter(read => read.parent === 'directory-00000').length).toBe(1);
  await expect(tree.getByText('file-00000', { exact: true })).toBeVisible();
  expect(await tree.locator('.n-tree-node').count()).toBeLessThan(80);
  expect(reads.length).toBeLessThanOrEqual(3);
});
