import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests/e2e',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://127.0.0.1:18080',
    ...(process.env.R62_EDGE_ACCEPTANCE === '1' ? { channel: 'msedge' as const } : {}),
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    launchOptions: process.env.R54_NATIVE === '1' ? { args: ['--host-resolver-rules=MAP agentplatform.lailihao.cloud 127.0.0.1', '--no-proxy-server'] } : {},
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
