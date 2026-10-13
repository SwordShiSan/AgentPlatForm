import { execFileSync } from 'node:child_process';
import { expect, test } from '@playwright/test';
import { fileURLToPath } from 'node:url';

const repo = fileURLToPath(new URL('../../../', import.meta.url));
const compose = ['compose', '-p', 'agentplatform-app', '-f', 'deploy/compose.r0.1.yaml'];
test('actual API stop and restart never reports healthy during failure', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('status')).toHaveText('连接正常');
  try {
    execFileSync('docker', [...compose, 'stop', 'api'], { cwd: repo, stdio: 'pipe' });
    await page.getByRole('button', { name: '重新检查' }).click();
    await expect(page.getByRole('status')).toHaveText('暂时不可用');
    await expect(page.getByRole('alert')).toContainText('暂时无法连接应用');
    await page.screenshot({ path: 'test-results/health-api-stopped.png', fullPage: true });
  } finally {
    execFileSync('docker', [...compose, 'up', '-d', '--no-build', '--wait', '--wait-timeout', '120', 'api'], { cwd: repo, stdio: 'pipe' });
  }
  await page.getByRole('button', { name: '重新检查' }).click();
  await expect(page.getByRole('status')).toHaveText('连接正常');
});
