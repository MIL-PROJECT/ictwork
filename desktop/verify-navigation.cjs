const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const { execFileSync } = require('node:child_process');

module.exports = async function verifyNavigation(page, origin, installed, profile, evidence) {
  page.setDefaultTimeout(15000);
  const aside = page.locator('aside').first();
  const go = async route => {
    await page.goto(origin + route);
    // load can finish before React mounts the authenticated layout.
    await (route === '/signin' ? page.locator('[name=loginId]') : aside.locator('nav > ul > li > div > a').first()).waitFor();
  };
  const top = name => aside.getByRole('link',{name,exact:true}).first();
  const pane = name => page.getByRole('navigation',{name,exact:true});
  async function owner(name) { await top(name).locator('xpath=self::*[@aria-current="location"]').waitFor(); }
  async function tab(group, name, path) {
    await pane(group).getByRole('link',{name,exact:true}).click();
    await page.waitForURL(origin + path);
    await pane(group).getByRole('link',{name,exact:true}).locator('xpath=self::*[@aria-current="page"]').waitFor();
  }
  async function snapshot(name) {
    // Let CSS transitions and the Electron compositor settle before visual evidence.
    await page.waitForTimeout(300);
    await page.screenshot({animations:'disabled',path:path.join(evidence,name)});
  }
  await go('/board');
  await require('./verify-operations.cjs')(page, origin, evidence);
  await require('./verify-operations-failures.cjs')(page, origin, evidence);
  await require('./verify-board.cjs')(page, origin, evidence);
  const expected = ['통합상황판','업무요청','업무 처리','일정','관리','AI 질의응답'];
  const labels = await aside.locator('nav > ul > li > div > a').allTextContents();
  assert.deepEqual(labels,expected);
  assert.equal(await aside.locator('a[href="/settings/me"]').count(),0);
  await snapshot('workflow-dashboard.png');
  await require('./verify-main-features.cjs')(page,origin,evidence);
  await go('/board');

  assert.equal(await pane('통합상황판').count(),0);
  await top('업무 통계').click();
  await page.waitForURL(origin+'/status');
  assert.equal(await pane('통합상황판').count(),0);
  await snapshot('statistics-sidebar.png');
  await owner('통합상황판');
  await page.locator('main a[href^="/tasks?tab=department"]').first().waitFor();
  const drillLink = page.locator('main a[href^="/tasks?tab=department"]').first();
  const drill = await drillLink.getAttribute('href');
  await drillLink.click();
  await page.waitForURL(origin + drill);
  await owner('업무 처리');
  await page.goBack();
  await page.waitForURL(origin+'/status');
  for(const [label, route] of [['운영현황','/operations'],['지시사항','/directives'],['근무자 현황','/duty'],['정보통신 현황','/ict-status']]) {
    await top(label).click();
    await page.waitForURL(origin+route);
    await owner('통합상황판');
  }

  await top('업무 처리').click();
  await page.waitForURL(origin+'/tasks?tab=department');
  assert.equal(await aside.getByRole('link',{name:'우리 부서 업무 등록',exact:true}).count(),0);
  const registrationList='/tasks?'+new URLSearchParams({tab:'department',fq:'등록 복귀 시험',page:'3',fb:'ONGOING'});
  await go(registrationList);
  const registerLink=()=>page.locator('main').getByRole('link',{name:'우리 부서 업무 등록',exact:true});
  await registerLink().waitFor();
  assert.equal(new URL(await registerLink().getAttribute('href'),origin).searchParams.get('returnTo'),registrationList);
  await snapshot('department-list-register-button.png');
  await registerLink().click();
  await page.waitForURL(url=>url.pathname==='/approvals/new');
  await page.getByRole('heading',{level:1,name:'우리 부서 업무 등록',exact:true}).waitFor();
  await owner('업무 처리');
  await top('업무등록대장').locator('xpath=self::*[@aria-current="page"]').waitFor();
  assert.equal(await pane('새 요청').count(),0);
  await page.reload();
  await page.locator('main').getByRole('link',{name:'목록으로',exact:true}).click();
  await page.waitForURL(origin+registrationList);
  await registerLink().click();
  await page.getByRole('heading',{level:1,name:'우리 부서 업무 등록',exact:true}).waitFor();
  await page.locator('header nav').getByRole('link',{name:'업무등록대장',exact:true}).click();
  await page.waitForURL(origin+registrationList);
  await go('/approvals/new?returnTo='+encodeURIComponent('https://example.com/tasks'));
  await page.locator('main').getByRole('link',{name:'목록으로',exact:true}).waitFor();
  assert.equal(await page.locator('main').getByRole('link',{name:'목록으로',exact:true}).getAttribute('href'),'/tasks?tab=department');
  await snapshot('processing-registration.png');
  await top('업무요청').click();
  await page.waitForURL(origin+'/approvals/request');
  await page.getByRole('button',{name:'다음',exact:true}).click();
  assert.equal(await page.getByRole('navigation',{name:'업무 요청 작성 단계'}).locator('[aria-current=step]').count(),1);
  assert.equal(await pane('화면 작업').count(),0);
  await require('./verify-guidance.cjs')(page,origin,evidence);
  await top('관리').click();
  await top('업무 유형 관리').click();
  await page.waitForURL(origin+'/admin/work-types');
  await page.getByRole('heading',{level:1,name:'업무 유형 관리',exact:true}).waitFor();
  await owner('관리');
  await snapshot('work-type-management.png');
  await top('업무요청').click();
  await top('요청 현황').click();
  await tab('요청 현황','공유받은 업무','/tasks?tab=viewable');
  await owner('업무요청');
  await tab('요청 현황','요청한 업무','/tasks?tab=mine');

  const me=await (await page.request.get(origin+'/api/me')).json();
  const cookies=()=>page.context().cookies(origin);
  async function post(route, data={}, expected=204) {
    const token=(await cookies()).find(cookie=>cookie.name==='XSRF-TOKEN');
    const response=await page.request.post(origin+route,{headers:{'X-XSRF-TOKEN':decodeURIComponent(token.value)},data});
    assert.equal(response.status(),expected,route+' '+await response.text());
    return response;
  }
  const types=await (await page.request.get(origin+'/api/work/types/all')).json();
  const ownType=types.find(type=>type.departmentId===me.departmentId && type.active && type.isOther);
  assert.ok(ownType,'Synthetic own-department other work type must exist');
  const marker='메뉴회귀'+Date.now();
  const body={
    departmentIntake:true,place:'시험실',content:marker,
    targets:[{departmentId:me.departmentId,types:[{workTypeId:ownType.typeId}]}],
    viewers:{departmentIds:[],personnelIds:[]},
  };
  await post('/api/work/requests',{...body,place:''},400);
  const created=await (await post('/api/work/requests',body,201)).json();
  const detail=await (await page.request.get(origin+'/api/work/requests/'+created.id+'?from=mine')).json();
  const itemId=detail.items[0].id;
  const list='/tasks?tab=mine&fq='+encodeURIComponent(marker)+'&page=1';
  await go(list);
  const requestLink=page.locator('main a[href^="/work/requests/'+created.id+'?"]').first();
  await requestLink.waitFor();
  const requestHref=await requestLink.getAttribute('href');
  assert.equal(new URL(requestHref,origin).searchParams.get('from'),'mine');
  await requestLink.click();
  const listCrumb=page.locator('header nav a').filter({hasText:'요청한 업무'}).first();
  await listCrumb.click();
  await page.waitForURL(origin+list);
  await owner('업무요청');
  await go('/tasks?tab=mine');
  await page.goBack();
  await page.waitForURL(origin+list);
  await page.reload();
  await owner('업무요청');

  await go('/approvals/receipts');
  await owner('업무 처리');
  await page.locator('main a[href^="/work/items/'+itemId+'?"]').first().waitFor();
  await post('/api/work/items/'+itemId+'/receive',{});
  await post('/api/work/items/'+itemId+'/plan',{plan:{autoComplete:false,slots:[{startDate:'2026-11-08',personnelIds:[],equipmentIds:[]}]}});
  await post('/api/work/items/'+itemId+'/complete-approval',{steps:[{kind:'APPROVE',accountId:me.accountId}]});
  const completed=await (await page.request.get(origin+'/api/work/items/'+itemId+'?from=department')).json();
  assert.equal(completed.status,'DONE');
  const rejected=await (await post('/api/work/requests',{...body,content:marker+'반려'},201)).json();
  const rejectedDetail=await (await page.request.get(origin+'/api/work/requests/'+rejected.id+'?from=mine')).json();
  await post('/api/work/items/'+rejectedDetail.items[0].id+'/reject',{reason:''},400);
  await post('/api/work/items/'+rejectedDetail.items[0].id+'/reject',{reason:'합성 반려 검사'});
  console.log('PASS live request validation/create, receipt, plan, completion approval, rejection validation and rejection');

  // The registration variant has distinct required fields and server behavior.
  const log=fs.readFileSync(path.join(profile,'service/mysql.log'),'utf8');
  const port=[...log.matchAll(/port: (\d+)\s+MySQL/g)].at(-1)[1];
  const credentials=JSON.parse(fs.readFileSync(path.join(profile,'service/credentials.json'),'utf8'));
  const sql=query=>execFileSync(path.join(installed,'resources/mysql/bin/mysql.exe'),[
    '--no-defaults','--protocol=TCP','-h','127.0.0.1','-P',port,'-u','root','-N','-B','ictwork',
  ],{input:query,encoding:'utf8',windowsHide:true,env:{...process.env,MYSQL_PWD:credentials.root}}).trim();
  const personnelId=Number(sql('SELECT personnel_id FROM account WHERE id='+me.accountId));
  const registration={
    kind:'REGISTER',title:marker+'등록',place:'시험실',content:marker+'등록',
    assigneePersonnelIds:[personnelId],targets:body.targets,
    workStartDate:'2026-11-08',steps:[{kind:'APPROVE',accountId:me.accountId}],
    viewers:{personnelIds:[personnelId]},
  };
  const registered=await (await post('/api/work/requests',registration,201)).json();
  const registeredDetail=await (await page.request.get(origin+'/api/work/requests/'+registered.id+'?from=mine')).json();
  assert.equal(registeredDetail.kind,'REGISTER');
  await require('./verify-registration.cjs')({page,origin,evidence,go,post,sql,me,ownType,registration});
  await go('/tasks?tab=department&fq='+encodeURIComponent(marker)+'&page=1');
  await owner('업무 처리');
  await page.locator('main a[href^="/work/items/'+registeredDetail.items[0].id+'?"]').first().waitFor();
  await top('일정').click();
  await page.waitForURL(origin+'/calendar');
  assert.equal(await aside.getByRole('button',{name:'일정 메뉴 펼치기·접기',exact:true}).count(),0);
  await owner('일정');

  await go('/approvals/todo?tab=myTurn');
  await owner('업무 처리');
  await tab('결재','진행 중 결재','/approvals/in-progress');
  await tab('결재','처리한 결재','/approvals/todo?tab=handled');
  const handled=await (await page.request.get(origin+'/api/approvals/handled')).json();
  assert.ok(handled.some(doc=>doc.title.includes(marker)));
  const docLink=page.locator('main a[href^="/approvals/docs/"]').first();
  await docLink.waitFor();
  await docLink.click();
  await owner('업무 처리');
  await page.locator('header nav a').filter({hasText:'처리한 결재'}).first().click();
  await page.waitForURL(origin+'/approvals/todo?tab=handled');

  await top('관리').click();
  await pane('조직·인원').getByRole('link',{name:'조직도',exact:true}).waitFor();
  assert.equal(await pane('조직·인원').getByRole('link').count(),2);
  await snapshot('management-organization.png');
  await pane('화면 작업').getByRole('link',{name:'조직관리',exact:true}).click();
  await owner('관리');
  await tab('조직·인원','인원','/department/people');
  await pane('화면 작업').getByRole('link',{name:'인원관리',exact:true}).click();
  await page.waitForURL(origin+'/admin/personnel');
  await page.locator('main a[href="/admin/attributes/personnel"]').click();
  await page.waitForURL(origin+'/admin/attributes/personnel');
  await owner('관리');
  await pane('조직·인원').getByRole('link',{name:'인원',exact:true}).locator('xpath=self::*[@aria-current="location"]').waitFor();
  await top('장비·구성도').click();
  await page.waitForURL(origin+'/department/equipment');
  await pane('장비·구성도').getByRole('link',{name:'장비',exact:true}).waitFor();
  assert.equal(await pane('장비·구성도').getByRole('link').count(),2);
  await tab('장비·구성도','구성도','/department/diagrams');
  await tab('장비·구성도','장비','/department/equipment');
  await pane('화면 작업').getByRole('link',{name:'장비관리',exact:true}).click();
  await page.waitForURL(origin+'/admin/equipment');
  await snapshot('department-equipment.png');
  await page.locator('main a[href="/admin/attributes/equipment"]').click();
  await page.waitForURL(origin+'/admin/attributes/equipment');
  await owner('관리');
  await pane('장비·구성도').getByRole('link',{name:'장비',exact:true}).locator('xpath=self::*[@aria-current="location"]').waitFor();
  await top('문서함').click();
  await page.waitForURL(origin+'/department/docs');
  await page.getByRole('heading',{name:'문서함',level:1,exact:true}).waitFor();
  await owner('관리');
  assert.equal(await pane('조직·인원').count(),0);
  assert.equal(await pane('장비·구성도').count(),0);
  await snapshot('management-documents.png');

  const api=origin+'/api/me';
  const flags=['ADMIN_ORG','ADMIN_PERSONNEL','ADMIN_EQUIPMENT','ADMIN_WORK_TYPE','ADMIN_SYSTEM','ADMIN_ATTRIBUTE'];
  for(const extra of [[],['ADMIN_ORG'],['ADMIN_PERSONNEL'],['ADMIN_SYSTEM']]) {
    await page.route(api,r=>r.fulfill({json:{...me,features:[...me.features.filter(f=>!flags.includes(f)),...extra]}}));
    await go('/department/org-chart');
    await pane('조직·인원').getByRole('link',{name:'조직도',exact:true}).waitFor();
    assert.equal(await aside.getByRole('link',{name:'업무 유형 관리',exact:true}).count(),0);
    assert.equal(await pane('조직·인원').getByRole('link').count(),2);
    assert.equal(await pane('화면 작업').getByRole('link',{name:'조직관리',exact:true}).count(),extra.includes('ADMIN_ORG')?1:0);
    await go('/admin/equipment');
    await page.locator('main h1').filter({hasText:/권한/}).waitFor();
    await go('/approvals/todo?tab=myTurn');
    await pane('결재').getByRole('link',{name:'내 차례',exact:true}).waitFor();
    if(extra.includes('ADMIN_SYSTEM')) await pane('화면 작업').getByRole('link',{name:'결재 경로',exact:true}).waitFor();
    assert.equal(await pane('화면 작업').getByRole('link',{name:'결재 경로',exact:true}).count(),extra.includes('ADMIN_SYSTEM')?1:0);
    await page.unroute(api);
  }

  await go('/board');
  await page.locator('header button').first().click();
  await aside.getByRole('link',{name:'통합상황판',exact:true}).waitFor();
  await page.locator('header button').first().click();
  await page.setViewportSize({width:860,height:900});
  await page.locator('header button').first().click();
  await aside.getByRole('link',{name:'통합상황판',exact:true}).waitFor();
  await page.keyboard.press('Escape');
  await aside.waitFor({state:'hidden'});
  await page.setViewportSize({width:1440,height:950});
  await go('/settings/me');
  const group=page.locator('[aria-labelledby=display-font-size-label]');
  const [save]=await Promise.all([page.waitForResponse(r=>r.url().endsWith('/api/me/font-size')&&r.request().method()==='PUT'),group.getByRole('button').last().click()]);
  assert.equal(save.status(),204);
  await page.reload();
  await page.locator('html[data-font-size=large]').waitFor({state:'attached'});
  await snapshot('large-font.png');
  await Promise.all([page.waitForResponse(r=>r.url().endsWith('/api/me/font-size')&&r.request().method()==='PUT'),group.getByRole('button').first().click()]);
  await page.evaluate(()=>{localStorage.setItem('language','en');localStorage.setItem('i18nextLng','en');localStorage.setItem('theme','dark');});
  await go('/tasks?tab=viewable');
  await page.locator('html[lang=en].dark').waitFor({state:'attached'});
  await page.getByRole('navigation',{name:'Request Status',exact:true}).getByRole('link',{name:'Shared Requests',exact:true}).waitFor();
  await snapshot('english-dark.png');
  await page.evaluate(()=>{localStorage.setItem('language','ko');localStorage.setItem('i18nextLng','ko');localStorage.setItem('theme','light');});

  const saved=sql("SELECT login_id,password_hash,password_change_required FROM account WHERE login_id IN ('sysadmin','bn1-co1-s2-m3');").split(/\r?\n/).map(row=>row.split('\t'));
  const seed='$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm';
  const hash='$2b$12$yk3bmg6ARaWcA.OpWOIw2O3qMsEMtghYpzecJw58Td8xB2fG/RxXu';
  try {
    sql("UPDATE account SET password_hash='"+hash+"',password_change_required=0 WHERE password_hash='"+seed+"' AND login_id IN ('sysadmin','bn1-co1-s2-m3');");
    for(const [user,status] of [['sysadmin',200],['bn1-co1-s2-m3',403]]) {
      await post('/api/auth/logout',{});
      await go('/signin');
      await page.locator('[name=loginId]').fill(user);
      await page.locator('[name=password]').fill('Local-Demo-Init!26');
      const [res]=await Promise.all([page.waitForResponse(r=>r.url().endsWith('/api/auth/login')&&r.request().method()==='POST'),page.locator('[name=password]').press('Enter')]);
      assert.equal(res.status(),200);
      assert.equal((await page.request.get(origin+'/api/admin/personnel')).status(),status);
      await go('/tasks?tab=department');
      await page.locator('main').getByRole('link',{name:'우리 부서 업무 등록',exact:true}).waitFor();
      assert.equal(await aside.getByRole('link',{name:'우리 부서 업무 등록',exact:true}).count(),0);
      await go('/admin/work-types');
      await page.locator('main h1').filter({hasText:/권한/}).waitFor();
      await go('/department/services');
      await page.locator('#work-guidance-search').waitFor();
      await go('/approvals/todo');
      if(status===200) await pane('화면 작업').getByRole('link',{name:'결재 경로',exact:true}).waitFor();
      else assert.equal(await pane('화면 작업').getByRole('link',{name:'결재 경로',exact:true}).count(),0);
    }
  } finally {
    sql(saved.map(([user,hash,required])=>"UPDATE account SET password_hash='"+hash+"',password_change_required="+required+" WHERE login_id='"+user+"';").join('\n'));
  }
  fs.writeFileSync(path.join(evidence,'workflow-result.json'), JSON.stringify({
    createdRequestId:created.id, completedItemId:itemId,
    registeredRequestId:registered.id, rejectedItemId:rejectedDetail.items[0].id,
  },null,2));
  console.log('PASS six-menu navigation, statistics drill-down, query tabs, list-state restoration, management actions, role/API permissions, mobile, large font, English/dark');
};
