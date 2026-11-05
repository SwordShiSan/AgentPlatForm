import { chromium } from '@playwright/test';
const browser = await chromium.launch({ args: ['--host-resolver-rules=MAP agentplatform.lailihao.cloud 127.0.0.1', '--no-proxy-server'], timeout: 15000 });
console.log('BROWSER_LAUNCHED');
try {
  const context = await browser.newContext({ proxy: { server: 'http://127.0.0.1:9', bypass: '*' } }); console.log('CONTEXT_CREATED');
  const page = await context.newPage(); console.log('PAGE_CREATED');
  await page.goto('data:text/html,<title>R54</title>'); console.log('LOCAL_PAGE_READY');
  if (process.env.R54_PREFLIGHT_NETWORK === '1') {
    const response = await page.goto('http://agentplatform.lailihao.cloud/api/v1/readiness', { timeout: 15000, waitUntil: 'domcontentloaded' });
    console.log(`HTTP_READINESS_STATUS=${response.status()}`);
    page.on('pageerror', error => console.log(`PAGE_ERROR=${error.message.slice(0, 500)}`));
    page.on('requestfailed', request => console.log(`REQUEST_FAILED=${new URL(request.url()).pathname}:${request.failure()?.errorText}`));
    const login = await page.goto('http://agentplatform.lailihao.cloud/login', { timeout: 15000, waitUntil: 'domcontentloaded' });
    console.log(`LOGIN_HTTP_STATUS=${login.status()}`);
    console.log(`LOGIN_RESPONSE=${JSON.stringify({headers: login.headers(), address: await login.serverAddr()})}`);
    await page.waitForTimeout(2000);
    console.log(`LOGIN_DOCUMENT=${(await page.locator('body').innerText()).slice(0, 1200)}`);
  }
} finally { await browser.close(); }
