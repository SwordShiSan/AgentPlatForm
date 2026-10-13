import { expect, test } from '@playwright/test';

test('tokens, keyboard, zoom, responsive themes and honest error routes', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('status')).toHaveText('连接正常');
  for (const width of [375, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    for (const theme of ['light', 'dark']) {
      await page.getByLabel('主题').selectOption(theme);
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
      const contrast = await page.evaluate(() => {
        const css = getComputedStyle(document.documentElement);
        const luminance = (token: string) => {
          const hex = css.getPropertyValue(token).trim().slice(1);
          const channels = [0, 2, 4].map(i => parseInt(hex.slice(i, i + 2), 16) / 255)
            .map(x => x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4);
          return channels[0]! * 0.2126 + channels[1]! * 0.7152 + channels[2]! * 0.0722;
        };
        return [['--text', '--panel'], ['--muted', '--canvas'], ['--accent', '--panel'], ['--success', '--success-bg'], ['--error', '--error-bg']]
          .map(([a, b]) => { const x = luminance(a!), y = luminance(b!); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); });
      });
      for (const ratio of contrast) expect(ratio).toBeGreaterThanOrEqual(4.5);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
      const target = await page.getByRole('button', { name: '重新检查' }).boundingBox();
      expect(target!.height).toBeGreaterThanOrEqual(44);
      await page.screenshot({ path: `test-results/baseline-${width}-${theme}.png`, fullPage: true });
    }
  }
  await page.setViewportSize({ width: 375, height: 900 });
  await page.evaluate(() => { document.documentElement.style.zoom = '2'; });
  await expect(page.getByRole('button', { name: '重新检查' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await page.evaluate(() => { document.documentElement.style.zoom = '1'; });
  await page.goto('/forbidden');
  await expect(page.getByRole('heading', { name: '没有访问权限。' })).toBeVisible();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: '跳到主要内容' })).toBeFocused();
  await page.goto('/not-a-page');
  await expect(page.getByRole('heading', { name: '页面不存在。' })).toBeVisible();
});
