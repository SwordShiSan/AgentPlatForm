import { test, expect, type Page } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

test.skip(process.env.R42_BROWSER_ACCEPTANCE !== '1', 'Requires isolated R4.2 real API/PG/MQ with deterministic provider');
test.use({ trace: 'off', screenshot: 'off' });
test.setTimeout(90_000);
const root=resolve(import.meta.dirname,'../../../.cache/r42-local/fixture');
async function login(page:Page,admin=false){
  await page.goto('/');await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel('账号',{exact:true}).fill(admin?'r42_admin':'r42_user');
  await page.getByLabel('密码',{exact:true}).fill(readFileSync(resolve(root,admin?'alice-password':'bob-password'),'utf8').trim());
  await page.getByRole('button',{name:'登录',exact:true}).click();
  await expect(page).toHaveURL(/\/app\/chat$/);
  await expect(page.getByRole('heading',{name:'普通聊天',exact:true})).toBeVisible();
}
async function begin(page:Page,text:string){
  await page.getByRole('button',{name:'新建对话',exact:true}).click();
  await page.getByLabel('先制定计划，再确认文本执行').check();
  await page.getByLabel('消息',{exact:true}).fill(text);await page.getByLabel('消息',{exact:true}).press('Control+Enter');
  await expect(page).toHaveURL(/\/app\/chat\/[0-9a-f-]+$/);
  await expect.poll(async()=>{
    const state=(await snapshot(page)).tasks.at(-1).status;
    if(!['QUEUED','RUNNING','WAITING_INPUT'].includes(state))throw new Error('Task reached '+state+' before an interaction');
    return state;
  },{timeout:60_000}).toBe('WAITING_INPUT');
}
async function snapshot(page:Page){const id=page.url().split('/').at(-1)!;const response=await page.request.get(`/api/v1/conversations/${id}/snapshot`);expect(response.ok()).toBe(true);return response.json();}

test('plan edit refresh confirmation stays on the same task and supports responsive themes',async({page,baseURL})=>{
  expect(baseURL).toBe('http://127.0.0.1:18102');await login(page);await begin(page,'fixture:plan 浏览器规划与文本续接');
  const card=page.getByTestId('plan-card');await expect(card).toBeVisible();
  await expect(card).toContainText('不授予文件、终端或其他工具权限');const before=await snapshot(page);const task=before.tasks.at(-1).taskId;
  await card.getByRole('button',{name:'直接编辑计划',exact:true}).click();
  await card.getByLabel('计划标题',{exact:true}).fill('浏览器修订计划');await card.getByLabel('计划正文',{exact:true}).fill('在同一任务中只输出文本结果666，不访问任何文件。');
  await card.getByRole('button',{name:'保存新修订',exact:true}).click();await expect(card).toContainText('浏览器修订计划');
  await page.reload();await expect(card).toContainText('浏览器修订计划');
  const folder=resolve(import.meta.dirname,'../../../.cache/r42-local/ui');mkdirSync(folder,{recursive:true});
  for(const width of [375,768,1440]){
    await page.setViewportSize({width,height:900});
    await expect(card.getByRole('button',{name:'按此执行文本',exact:true})).toBeVisible();
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1)).toBe(true);
    await page.screenshot({path:resolve(folder,`plan-${width}.png`),fullPage:true});
  }
  for(const theme of ['light','dark']){await page.getByLabel('主题',{exact:true}).selectOption(theme);await page.screenshot({path:resolve(folder,`plan-${theme}.png`),fullPage:true});}
  await page.evaluate(()=>{document.documentElement.style.zoom='2';});
  await expect(card.getByRole('button',{name:'按此执行文本',exact:true})).toBeVisible();await page.evaluate(()=>{document.documentElement.style.zoom='';});
  await card.getByRole('button',{name:'按此执行文本',exact:true}).focus();await page.keyboard.press('Enter');
  await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('666');await expect(card).toHaveCount(0);
  await expect.poll(async()=>(await snapshot(page)).tasks.at(-1).status).toBe('SUCCEEDED');
  const after=await snapshot(page);expect(after.tasks.at(-1).taskId).toBe(task);expect(after.messages.filter((m:{taskId:string;role:string})=>m.taskId===task&&m.role==='USER')).toHaveLength(1);
});

test('required question is not a new user message and plan-only delivery makes no extra model call',async({page})=>{
  await login(page);await begin(page,'fixture:plan-question 先澄清再仅交付方案');
  const question=page.getByTestId('question-card');await expect(question).toBeVisible();await question.getByLabel('简洁',{exact:true}).check();await question.getByLabel('补充说明',{exact:true}).fill('保留中文😀');
  await question.getByRole('button',{name:'提交回答',exact:true}).click();const plan=page.getByTestId('plan-card');await expect(plan).toBeVisible();await page.reload();await expect(plan).toBeVisible();
  const before=await snapshot(page);const task=before.tasks.at(-1).taskId;
  await plan.getByRole('button',{name:'仅交付方案',exact:true}).click();await expect(plan).toHaveCount(0);await expect(page.locator('.chat-message.assistant .chat-text').last()).toContainText('明确用户提供的文本需求');
  const after=await snapshot(page);expect(after.tasks.at(-1).taskId).toBe(task);expect(after.tasks.at(-1).status).toBe('SUCCEEDED');
  expect(after.messages.filter((m:{taskId:string;role:string})=>m.taskId===task&&m.role==='USER')).toHaveLength(1);
  const response=await page.request.get(`/api/v1/tasks/${task}/usage`);expect(response.ok()).toBe(true);const usage=await response.json();expect(Array.isArray(usage)?usage:usage.items).toHaveLength(3);
});

test('admin quota exemption does not permit reading another user plan',async({page,browser})=>{
  await login(page);await begin(page,'fixture:plan 个人空间隔离');await expect(page.getByTestId('plan-card')).toBeVisible();const current=await snapshot(page);const task=current.tasks.at(-1).taskId;
  const context=await browser.newContext();const other=await context.newPage();await login(other,true);
  await expect(other.getByTestId('daily-quota')).toContainText('管理员额度不限');const denied=await other.request.get(`/api/v1/tasks/${task}/plan`);expect(denied.status()).toBe(404);
  await page.getByRole('button',{name:'取消回复',exact:true}).click();await expect(page.getByTestId('plan-card')).toHaveCount(0);expect((await snapshot(page)).tasks.at(-1).status).toBe('CANCELLED');
  await context.close();
});

test('task workspace binding directory grants mode changes and revocation survive refresh',async({page})=>{
  await login(page);await page.getByLabel('本次工作区',{exact:true}).selectOption({label:'R42 browser metadata'});
  await page.getByLabel('本次权限',{exact:true}).selectOption('FULL');await begin(page,'fixture:plan R42目录授权');
  const panel=page.getByTestId('task-permissions');await expect(page.getByTestId('plan-card')).toBeVisible();
  await expect(panel.getByLabel('权限模式',{exact:true})).toHaveValue('FULL');
  const task=(await snapshot(page)).tasks.at(-1).taskId;
  const before=await (await page.request.get(`/api/v1/tasks/${task}/configuration`)).json();
  await panel.getByLabel('授权目录',{exact:true}).selectOption({label:'docs'});
  let loseResponse=true;
  await page.route('**/api/v1/tasks/*/grants',async route=>{
    if(route.request().method()==='POST'&&loseResponse){loseResponse=false;const committed=await route.fetch();expect(committed.status()).toBe(200);await route.abort('failed');}
    else await route.continue();
  });
  await panel.getByRole('button',{name:'授予目录范围',exact:true}).click();
  await expect(panel.getByRole('button',{name:'重试同一权限请求',exact:true})).toBeVisible();
  await panel.getByRole('button',{name:'重试同一权限请求',exact:true}).click();
  await expect(panel.getByRole('button',{name:'撤销授权',exact:true})).toBeVisible();
  const once=await (await page.request.get(`/api/v1/tasks/${task}/permissions`)).json();expect(once.grants).toHaveLength(1);expect(once.version).toBe(2);
  await page.reload();await expect(panel.getByRole('button',{name:'撤销授权',exact:true})).toBeVisible();
  await panel.getByLabel('权限模式',{exact:true}).selectOption('RISK_BASED');
  await expect.poll(async()=>(await (await page.request.get(`/api/v1/tasks/${task}/permissions`)).json()).mode).toBe('RISK_BASED');
  await panel.getByRole('button',{name:'撤销授权',exact:true}).click();await expect(panel).toContainText('已撤销');
  const after=await (await page.request.get(`/api/v1/tasks/${task}/configuration`)).json();expect(after.configHash).toBe(before.configHash);
  for(const width of [375,768,1440]){await page.setViewportSize({width,height:900});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1)).toBe(true);}
  await page.getByTestId('plan-card').getByRole('button',{name:'仅交付方案',exact:true}).click();
  await expect(panel).toContainText('权限只读');
});

test('two tabs cannot overwrite a permission revision and missing csrf or version fails',async({page,browser})=>{
  await login(page);await begin(page,'fixture:plan 权限版本冲突');await expect(page.getByTestId('plan-card')).toBeVisible();
  const task=(await snapshot(page)).tasks.at(-1).taskId;
  const state=await (await page.request.get(`/api/v1/tasks/${task}/permissions`)).json();
  const token=(await (await page.request.get('/api/v1/auth/csrf')).json()).csrfToken;
  const headers={'Origin':'http://127.0.0.1:18102','X-CSRF-Token':token,'Idempotency-Key':crypto.randomUUID(),'If-Match':`"${state.version}"`};
  const secondTab=await page.context().newPage();await secondTab.goto(page.url());
  await expect(secondTab.getByTestId('task-permissions').getByLabel('权限模式',{exact:true})).toHaveValue('RISK_BASED',{timeout:30_000});
  const first=await page.request.patch(`/api/v1/tasks/${task}/permissions`,{headers,data:{mode:'FULL'}});expect(first.status()).toBe(200);
  const stale=await secondTab.request.patch(`/api/v1/tasks/${task}/permissions`,{headers:{...headers,'Idempotency-Key':crypto.randomUUID()},data:{mode:'RISK_BASED'}});expect(stale.status()).toBe(412);await secondTab.reload();await expect(secondTab.getByTestId('task-permissions').getByLabel('权限模式',{exact:true})).toHaveValue('FULL');await secondTab.close();
  const noVersion=await page.request.patch(`/api/v1/tasks/${task}/permissions`,{headers:{Origin:headers.Origin,'X-CSRF-Token':token,'Idempotency-Key':crypto.randomUUID()},data:{mode:'FULL'}});expect(noVersion.status()).toBe(428);
  const noCsrf=await page.request.patch(`/api/v1/tasks/${task}/permissions`,{headers:{Origin:headers.Origin,'Idempotency-Key':crypto.randomUUID(),'If-Match':'"2"'},data:{mode:'FULL'}});expect(noCsrf.status()).toBe(403);
  const foreign=await browser.newContext();const other=await foreign.newPage();await login(other,true);
  expect((await other.request.get(`/api/v1/tasks/${task}/permissions`)).status()).toBe(404);await foreign.close();
  await page.reload();await expect(page.getByTestId('task-permissions').getByLabel('权限模式',{exact:true})).toHaveValue('FULL');
  await page.getByTestId('plan-card').getByRole('button',{name:'仅交付方案',exact:true}).click();
});
