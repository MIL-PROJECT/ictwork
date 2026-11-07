const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const { execFileSync } = require('node:child_process');

module.exports = async function verifyNavigation(page, origin, installed, profile, evidence) {
  page.setDefaultTimeout(15000);
  const go = route => page.goto(origin + route);
  const aside = page.locator('aside').first();
  const menu = name => aside.getByRole('link', { name, exact: true });
  const tabs = name => page.getByRole('navigation', { name, exact: true });
  for (const name of ['조직·인원', '장비·구성도', '부서 업무', '문서함']) await menu(name).waitFor();
  await aside.getByRole('heading', { name: '관리', exact: true }).waitFor();
  assert.equal(await aside.locator('a[href="/settings/me"]').count(), 0);
  assert.equal(await aside.getByRole('heading', { name: '부서관리', exact: true }).count(), 0);

  for (const [group, routes] of [
    ['조직·인원', ['/department/people','/admin/organization','/admin/personnel']],
    ['장비·구성도', ['/department/diagrams','/admin/equipment']],
    ['부서 업무', ['/admin/work-types','/department/services']],
  ]) {
    await menu(group).click();
    for (const route of routes) {
      await tabs(group).locator('a[href='+JSON.stringify(route)+']').click();
      await tabs(group).locator('a[aria-current=page][href='+JSON.stringify(route)+']').waitFor();
      await menu(group).locator('xpath=self::*[@aria-current="location"]').waitFor();
    }
  }
  await page.locator('#work-guidance-search').fill('xyz-no-matching-work-type');
  await page.getByText('표시할 업무 유형이 없습니다.', {exact:true}).waitFor();
  await page.locator('#work-guidance-search').fill('');
  await page.locator('main dl dt').first().waitFor();
  await page.screenshot({path:path.join(evidence,'work-guidance.png')});
  await page.route('**/api/work/types/all', r => r.fulfill({status:500,json:{message:'Synthetic failure'}}));
  await go('/department/services');
  await page.getByRole('alert').filter({hasText:'업무 안내를 불러오지 못했습니다.'}).waitFor();
  await page.unroute('**/api/work/types/all');
  await page.getByRole('button',{name:'다시 시도',exact:true}).click();
  await page.locator('main dl dt').first().waitFor();

  await go('/admin/department-services?legacy=1');
  await page.waitForURL('**/admin/work-types?legacy=1');
  await go('/approvals/request');
  assert.equal(await page.getByRole('navigation',{name:'업무 요청 작성 단계'}).getByRole('button').count(),2);
  await page.locator('main a[href="/department/services"]').click();
  await page.waitForURL('**/department/services');
  await page.getByRole('button',{name:'이전으로',exact:true}).click();
  await page.waitForURL('**/approvals/request');

  for (const base of ['/department/equipment','/department/diagrams']) {
    await go(base);
    const detail=page.locator('main a[href^="'+base+'/"]:not([href$="/new"])').first();
    await detail.waitFor();
    await detail.click();
    await menu('장비·구성도').locator('xpath=self::*[@aria-current="location"]').waitFor();
    await page.locator('header nav a[href="'+base+'"]:visible').click();
    await page.waitForURL('**'+base);
  }
  await go('/department/docs?scope=all&q='+encodeURIComponent('인수인계_메모'));
  const [preview]=await Promise.all([
    page.waitForResponse(r=>/\/api\/doc-nodes\/\d+\/view$/.test(r.url())),
    page.getByRole('button',{name:'인수인계_메모.txt',exact:true}).first().click(),
  ]);
  assert.equal(preview.status(),200);
  await page.getByRole('dialog').waitFor();
  await page.screenshot({path:path.join(evidence,'document-preview.png')});


  await go('/settings/me');
  const fontGroup=page.locator('[aria-labelledby=display-font-size-label]');
  const [fontSave]=await Promise.all([
    page.waitForResponse(r=>r.url().endsWith('/api/me/font-size')&&r.request().method()==='PUT'),
    fontGroup.getByRole('button').last().click(),
  ]);
  assert.equal(fontSave.status(),204);
  await page.reload();
  await page.locator('html[data-font-size=large]').waitFor({state:'attached'});
  await page.screenshot({path:path.join(evidence,'large-font.png')});
  const [fontReset]=await Promise.all([
    page.waitForResponse(r=>r.url().endsWith('/api/me/font-size')&&r.request().method()==='PUT'),
    fontGroup.getByRole('button').first().click(),
  ]);
  assert.equal(fontReset.status(),204);
  const api=origin+'/api/me';
  const who=await (await page.request.get(api)).json();
  const flags=['ADMIN_ORG','ADMIN_PERSONNEL','ADMIN_EQUIPMENT','ADMIN_WORK_TYPE','ADMIN_SYSTEM'];
  for (const extra of [[], ['ADMIN_ORG'], ['ADMIN_PERSONNEL'], ['ADMIN_SYSTEM']]) {
    await page.route(api,r=>r.fulfill({json:{...who,features:[...who.features.filter(f=>!flags.includes(f)),...extra]}}));
    await go('/department/org-chart');
    await tabs('조직·인원').locator('a[aria-current=page]').waitFor();
    assert.equal(await tabs('조직·인원').getByRole('link').count(),2+extra.filter(f=>['ADMIN_ORG','ADMIN_PERSONNEL'].includes(f)).length);
    await go('/department/services');
    await page.locator('#work-guidance-search').waitFor();
    assert.equal(await tabs('부서 업무').count(),extra.includes('ADMIN_SYSTEM') ? 1 : 0);
    for (const route of ['/admin/equipment','/admin/work-types']) {
      await go(route);
      await page.locator('main h1').filter({hasText:/권한/}).waitFor();
    }
    await page.unroute(api);
  }

  const log=fs.readFileSync(path.join(profile,'service/mysql.log'),'utf8');
  const port=[...log.matchAll(/port: (\d+)\s+MySQL/g)].at(-1)[1];
  const credentials=JSON.parse(fs.readFileSync(path.join(profile,'service/credentials.json'),'utf8'));
  const sql = query => execFileSync(path.join(installed,'resources/mysql/bin/mysql.exe'),[
    '--no-defaults','--protocol=TCP','-h','127.0.0.1','-P',port,'-u','root','-N','-B','ictwork',
  ],{input:query,encoding:'utf8',windowsHide:true,env:{...process.env,MYSQL_PWD:credentials.root}}).trim();
  const saved=sql("SELECT login_id,password_hash,password_change_required FROM account WHERE login_id IN ('sysadmin','bn1-co1-s2-m3');").split(/\r?\n/).map(row=>row.split('\t'));
  assert.equal(saved.length,2);
  const seed='$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm';
  const hash='$2b$12$yk3bmg6ARaWcA.OpWOIw2O3qMsEMtghYpzecJw58Td8xB2fG/RxXu';
  try {
    sql("UPDATE account SET password_hash='"+hash+"',password_change_required=0 WHERE password_hash='"+seed+"' AND login_id IN ('sysadmin','bn1-co1-s2-m3');");
    for(const [user,status] of [['sysadmin',200],['bn1-co1-s2-m3',403]]) {
      const me=page;
      {
        const cookies=await page.context().cookies(origin);
        const token=cookies.find(c=>c.name==='XSRF-TOKEN');
        await me.request.post(origin+'/api/auth/logout',{headers:{'X-XSRF-TOKEN':decodeURIComponent(token.value)}});
        await me.goto(origin+'/signin');
        await me.locator('[name=loginId]').fill(user);
        await me.locator('[name=password]').fill('Local-Demo-Init!26');
        const [res]=await Promise.all([
          me.waitForResponse(r=>r.url().endsWith('/api/auth/login')&&r.request().method()==='POST'),
          me.locator('[name=password]').press('Enter'),
        ]);
        assert.equal(res.status(),200,user);
        assert.equal((await me.request.get(origin+'/api/admin/personnel')).status(),status,user);
        await me.goto(origin+'/admin/personnel');
        if(status===403) await me.locator('main h1').filter({hasText:/권한/}).waitFor();
        else await me.getByRole('navigation',{name:'조직·인원',exact:true}).getByRole('link',{name:'인원관리',exact:true}).waitFor();
      }
    }
  } finally {
    sql(saved.map(([user,hash,required])=>"UPDATE account SET password_hash='"+hash+"',password_change_required="+required+" WHERE login_id='"+user+"';").join('\n'));
  }
  console.log('PASS grouped navigation, latest two-step request, current work guidance, detail return, preview, feature combinations, real role/API permissions');
};
