import { expect, test } from '@playwright/test';

test('real health API, usable shell, route and mobile layout', async ({ page, request }) => {
  const response = await request.get('/api/v1/health');
  expect(response.ok()).toBeTruthy();
  expect(await response.json()).toEqual({ status: 'UP' });
  await page.goto('/');
  await expect(page.getByRole('status')).toHaveText('连接正常');
  await page.getByRole('button', { name: '连接详情' }).click();
  await expect(page.getByText('GET /api/v1/health')).toBeVisible();
  await page.screenshot({ path: 'test-results/health-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 375, height: 812 });
  await expect(page.getByRole('button', { name: '重新检查' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await page.screenshot({ path: 'test-results/health-mobile.png', fullPage: true });
  await page.goto('/missing');
  await expect(page.getByRole('heading', { name: '页面不存在。' })).toBeVisible();
  await page.getByRole('link', { name: '返回首页' }).click();
  await expect(page.getByRole('status')).toHaveText('连接正常');
});

test('network failure is visible and retry recovers against real API', async ({ page }) => {
  await page.route('**/api/v1/health', route => route.abort('failed'));
  await page.goto('/');
  await expect(page.getByRole('alert')).toContainText('暂时无法连接应用');
  await expect(page.getByRole('status')).toHaveText('暂时不可用');
  await page.screenshot({ path: 'test-results/health-offline.png', fullPage: true });
  await page.unroute('**/api/v1/health');
  await page.getByRole('button', { name: '重新检查' }).click();
  await expect(page.getByRole('status')).toHaveText('连接正常');
});
