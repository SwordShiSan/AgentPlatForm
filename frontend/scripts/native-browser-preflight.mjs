import { chromium } from '@playwright/test';
const browser = await chromium.launch({ args: ['--host-resolver-rules=MAP agentplatform.lailihao.cloud 127.0.0.1', '--no-proxy-server'], timeout: 15000 });
console.log('BROWSER_LAUNCHED');
try {
  const context = await browser.newContext(); console.log('CONTEXT_CREATED');
  const page = await context.newPage(); console.log('PAGE_CREATED');
  await page.goto('data:text/html,<title>R54</title>'); console.log('LOCAL_PAGE_READY');
  if (process.env.R54_PREFLIGHT_NETWORK === '1') {
    const response = await page.goto('http://agentplatform.lailihao.cloud/api/v1/readiness', { timeout: 15000, waitUntil: 'domcontentloaded' });
    console.log(`HTTP_READINESS_STATUS=${response.status()}`);
  }
} finally { await browser.close(); }
