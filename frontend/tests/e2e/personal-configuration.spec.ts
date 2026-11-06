import { test, expect, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

test.skip(process.env.R61_BROWSER_ACCEPTANCE !== '1', 'Requires the isolated R6.1 API/PG/MQ fixture');
test.use({ trace: 'off', screenshot: 'off' });
test.setTimeout(90_000);
const root=resolve(import.meta.dirname,'../../../.cache/r61-local/fixture');
const workspace=JSON.parse(readFileSync(resolve(root,'workspace-metadata.json'),'utf8')).workspaceId as string;

async function login(page:Page,admin=true){
  await page.goto('/');await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel('账号',{exact:true}).fill(admin?'r61_admin':'r61_user');
  await page.getByLabel('密码',{exact:true}).fill(readFileSync(resolve(root,admin?'admin-password':'user-password'),'utf8').trim());
  await page.getByRole('button',{name:'登录',exact:true}).click();await expect(page).toHaveURL(/\/app\/chat$/);
}
async function csrf(page:Page){return (await (await page.request.get('/api/v1/auth/csrf')).json()).csrfToken as string;}

test('personal and workspace defaults are frozen into a task and remain unchanged',async({page,baseURL})=>{
  await login(page);
  const personal=await (await page.request.get('/api/v1/me/preferences')).json();
  expect((await page.request.patch('/api/v1/me/preferences',{headers:{Origin:baseURL!,'X-CSRF-Token':await csrf(page),'If-Match':`"${personal.version}"`},data:{defaultPlanEnabled:null,defaultApprovalMode:null}})).status()).toBe(200);
  const original=await (await page.request.get(`/api/v1/workspaces/${workspace}/settings`)).json();
  expect((await page.request.patch(`/api/v1/workspaces/${workspace}/settings`,{headers:{Origin:baseURL!,'X-CSRF-Token':await csrf(page),'If-Match':`"${original.version}"`},data:{defaultPlanEnabled:null,defaultApprovalMode:null}})).status()).toBe(200);
  await page.goto('/app/settings/general');
  await page.getByLabel('默认计划模式').selectOption('true');
  await page.getByLabel('默认权限模式').selectOption('FULL');
  await page.getByRole('button',{name:'保存',exact:true}).click();await expect(page.getByRole('status')).toContainText('设置已保存');
  await page.goto(`/app/workspaces/${workspace}/settings`);
  await page.getByLabel('默认计划模式').selectOption('false');
  await page.getByLabel('默认权限模式').selectOption('RISK_BASED');
  await page.getByRole('button',{name:'保存',exact:true}).click();await expect(page.getByRole('status')).toContainText('设置已保存');
  await expect(page.getByLabel('已保存的生效配置')).toContainText('WORKSPACE');
  for(const width of [375,768,1440]){await page.setViewportSize({width,height:900});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1)).toBe(true);}
  await page.getByLabel('默认模型').focus();await expect(page.getByLabel('默认模型')).toBeFocused();await page.keyboard.press('Tab');await expect(page.getByLabel('密钥来源')).toBeFocused();
  await page.getByLabel('主题',{exact:true}).selectOption('dark');await page.getByLabel('主题',{exact:true}).selectOption('light');
  await page.evaluate(()=>{document.documentElement.style.zoom='2';});await expect(page.getByLabel('默认模型')).toBeVisible();await page.evaluate(()=>{document.documentElement.style.zoom='';});
  await page.goto('/app/chat');await page.getByLabel('本次工作区').selectOption(workspace);
  await expect(page.getByText(/本次生效模型/)).toBeVisible();
  await page.getByLabel('消息',{exact:true}).fill('R61 配置验收');
  const response=page.waitForResponse(value=>value.url().endsWith('/api/v1/conversations')&&value.request().method()==='POST');
  await page.getByRole('button',{name:'发送',exact:true}).click();const created=await response;expect(created.status()).toBe(201);
  const task=(await created.json()).initialTask.taskId as string;
  await expect.poll(async()=>{const result=await page.request.get(`/api/v1/tasks/${task}`);return (await result.json()).status;},{timeout:60_000}).toBe('SUCCEEDED');
  const before=await (await page.request.get(`/api/v1/tasks/${task}/configuration`)).json();
  expect(before.values.planMode).toBe(false);expect(before.values.permissionMode).toBe('RISK_BASED');
  expect(before.values.window).toBe(262144);expect(before.values.maxOutput).toBe(4096);
  expect(before.values.resolution.sources.planMode).toBe('WORKSPACE');expect(before.values.resolution.sources.permissionMode).toBe('WORKSPACE');
  const state=await (await page.request.get(`/api/v1/workspaces/${workspace}/settings`)).json();
  const changed=await page.request.patch(`/api/v1/workspaces/${workspace}/settings`,{headers:{Origin:baseURL!,'X-CSRF-Token':await csrf(page),'If-Match':`"${state.version}"`},data:{defaultApprovalMode:'FULL'}});
  expect(changed.status()).toBe(200);
  const after=await (await page.request.get(`/api/v1/tasks/${task}/configuration`)).json();expect(after.configHash).toBe(before.configHash);expect(after.values).toEqual(before.values);
  await page.reload();await page.getByText('当前任务配置').click();await expect(page.getByText(/冻结来源/)).toBeVisible();
});

test('stale versions, missing credentials and another user are rejected',async({page,browser,baseURL})=>{
  await login(page);const current=await (await page.request.get(`/api/v1/workspaces/${workspace}/settings`)).json();
  const headers={Origin:baseURL!,'X-CSRF-Token':await csrf(page),'If-Match':`"${current.version}"`};
  const first=await page.request.patch(`/api/v1/workspaces/${workspace}/settings`,{headers,data:{defaultApprovalMode:'RISK_BASED'}});expect(first.status()).toBe(200);
  const stale=await page.request.patch(`/api/v1/workspaces/${workspace}/settings`,{headers,data:{defaultApprovalMode:'FULL'}});expect(stale.status()).toBe(412);
  const missing=await page.request.get('/api/v1/effective-config?credentialSource=USER');const preview=await missing.json();expect(preview.canSubmit).toBe(false);expect(preview.blockers).toContain('CREDENTIAL_SOURCE_UNAVAILABLE');
  const other=await browser.newContext();const user=await other.newPage();await login(user,false);
  expect((await user.request.get(`/api/v1/workspaces/${workspace}/settings`)).status()).toBe(404);await other.close();
});

test('lost response retries the original accepted task after defaults change',async({page,baseURL})=>{
  await login(page);let committedId='';let loseResponse=true;
  await page.route('**/api/v1/conversations',async route=>{
    if(route.request().method()==='POST'&&loseResponse){
      loseResponse=false;const response=await route.fetch();expect(response.status()).toBe(201);committedId=(await response.json()).initialTask.taskId;await route.abort('failed');
    } else await route.continue();
  });
  await page.getByLabel('消息',{exact:true}).fill('R61 响应丢失测试');
  await page.getByRole('button',{name:'发送',exact:true}).click();
  await expect.poll(()=>committedId,{timeout:30_000}).not.toBe('');await expect(page.getByRole('button',{name:'重试同一条消息'})).toBeEnabled();
  const state=await (await page.request.get('/api/v1/me/preferences')).json();
  const changed=await page.request.patch('/api/v1/me/preferences',{headers:{Origin:baseURL!,'X-CSRF-Token':await csrf(page),'If-Match':`"${state.version}"`},data:{defaultPlanEnabled:!(state.defaultPlanEnabled as boolean)}});expect(changed.status()).toBe(200);
  await page.getByRole('button',{name:'重试同一条消息'}).click();await expect(page).toHaveURL(/\/app\/chat\/[0-9a-f-]+$/);
  const conversation=page.url().split('/').at(-1)!;const snapshot=await (await page.request.get(`/api/v1/conversations/${conversation}/snapshot`)).json();
  expect(snapshot.tasks).toHaveLength(1);expect(snapshot.tasks[0].taskId).toBe(committedId);
});
