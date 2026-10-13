import { execFileSync } from 'node:child_process';
import { expect, test } from '@playwright/test';
import { fileURLToPath } from 'node:url';

const repo = fileURLToPath(new URL('../../../', import.meta.url));
const compose = ['compose', '-p', 'agentplatform-app', '-f', 'deploy/compose.r0.1.yaml'];
function apiAction(action: 'stop' | 'start') {
  if (process.env.CLOUD_SSH_HOST && process.env.CLOUD_SSH_KEY) {
    const command = action === 'stop' ? 'docker stop -t 30 agentplatform-app-api-1' : 'docker start agentplatform-app-api-1';
    execFileSync('ssh', ['-o', 'BatchMode=yes', '-i', process.env.CLOUD_SSH_KEY, process.env.CLOUD_SSH_HOST, command], { stdio: 'pipe', timeout: 60000 });
  } else {
    execFileSync('docker', action === 'stop' ? [...compose, 'stop', 'api'] : [...compose, 'up', '-d', '--no-build', '--wait', '--wait-timeout', '120', 'api'], { cwd: repo, stdio: 'pipe' });
  }
}
test('actual API stop and restart never reports healthy during failure', async ({ page }) => {
  test.setTimeout(90_000);
  await page.goto('/');
  await expect(page.getByRole('status')).toHaveText('连接正常');
  try {
    apiAction('stop');
    await page.getByRole('button', { name: '重新检查' }).click();
    await expect(page.getByRole('status')).toHaveText('暂时不可用');
    await expect(page.getByRole('alert')).toContainText('暂时无法连接应用');
    await page.screenshot({ path: 'test-results/health-api-stopped.png', fullPage: true });
  } finally {
    apiAction('start');
    await expect.poll(async () => (await page.request.get('/api/v1/health')).status(), { timeout: 40000 }).toBe(200);
  }
  await page.getByRole('button', { name: '重新检查' }).click();
  await expect(page.getByRole('status')).toHaveText('连接正常');
});
