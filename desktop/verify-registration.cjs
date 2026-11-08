const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { request } = require('playwright');

module.exports = async function verifyRegistration({page,origin,evidence,go,post,sql,me,ownType,registration}) {
  const title='목록등록UI'+Date.now();
  const list='/tasks?tab=department&fq='+encodeURIComponent(title)+'&page=2';
  await go(list);
  await page.locator('main').getByRole('link',{name:'우리 부서 업무 등록',exact:true}).click();
  await page.getByRole('heading',{level:1,name:'우리 부서 업무 등록',exact:true}).waitFor();
  const options=await (await page.request.get(origin+'/api/work/types/own')).json();
  const type=options.find(t=>t.typeId===ownType.typeId);
  assert.ok(type);
  await page.locator('#request-types').getByRole('checkbox',{name:type.label,exact:true}).check();
  await page.locator('#request-title').fill(title);
  await page.locator('#request-place').fill('격리 시험실');
  await page.locator('#request-content').fill('목록에서 등록하고 같은 검색 조건으로 돌아오는 합성 업무');
  await page.locator('form input[type=date]').first().fill('2026-11-08');
  for(const id of ['request-assignees','request-viewers']) {
    const section=page.locator('#'+id);
    await section.getByRole('searchbox').fill(me.displayName);
    await section.getByRole('treeitem').filter({hasText:me.displayName}).last().click();
  }
  const [response]=await Promise.all([
    page.waitForResponse(r=>r.url()===origin+'/api/work/requests'&&r.request().method()==='POST'),
    page.getByRole('button',{name:'보내기 (본인 결재)',exact:true}).click(),
  ]);
  assert.equal(response.status(),201,await response.text());
  const created=await response.json();
  await page.waitForURL(url=>url.pathname==='/work/requests/'+created.id);
  assert.equal(new URL(page.url()).searchParams.get('from'),'mine');
  assert.equal(new URL(page.url()).searchParams.get('returnTo'),list);
  await page.locator('main').getByRole('link',{name:'목록으로',exact:true}).waitFor();
  await page.screenshot({animations:'disabled',path:path.join(evidence,'registration-created-detail.png')});
  await page.reload();
  await page.locator('main').getByRole('link',{name:'목록으로',exact:true}).click();
  await page.waitForURL(origin+list);
  const uiDetail=await (await page.request.get(origin+'/api/work/requests/'+created.id+'?from=mine')).json();
  assert.equal(uiDetail.kind,'REGISTER');
  assert.equal(uiDetail.phase,'SENT');
  assert.equal(uiDetail.items.length,1);
  await page.locator('main a[href^="/work/items/'+uiDetail.items[0].id+'?"]').first().waitFor();

  // Multi-person approval must create no work item until the assigned approver acts.
  const login='bn1-co1-s2-m3';
  const [accountId,personnelId,oldHash,required]=sql("SELECT id,personnel_id,password_hash,password_change_required FROM account WHERE login_id='"+login+"'").split('\t');
  assert.notEqual(Number(accountId),me.accountId);
  const pending=await (await post('/api/work/requests',{
    ...registration,title:title+'결재대기',steps:[{kind:'REVIEW',accountId:me.accountId},{kind:'APPROVE',accountId:Number(accountId)}],
  },201)).json();
  const pendingDetail=await (await page.request.get(origin+'/api/work/requests/'+pending.id+'?from=mine')).json();
  assert.equal(pendingDetail.phase,'SEND_APPROVAL');
  assert.equal(pendingDetail.items.length,0);
  const docId=pendingDetail.docs.find(d=>d.docType==='SEND'&&d.status==='IN_PROGRESS').id;
  await post('/api/approvals/docs/'+docId+'/approve',{},409);
  await go('/work/requests/'+pending.id+'?from=mine&returnTo='+encodeURIComponent(list));
  await page.locator('main').getByRole('link',{name:'목록으로',exact:true}).waitFor();
  await page.getByText('발송 결재중',{exact:true}).first().waitFor();
  await page.locator('main').getByRole('link',{name:'목록으로',exact:true}).click();
  await page.waitForURL(origin+list);

  const client=await request.newContext({baseURL:origin});
  let ordinary;
  try {
    // Only this isolated synthetic account is changed, then restored in finally.
    sql("UPDATE account SET password_hash='$2b$12$yk3bmg6ARaWcA.OpWOIw2O3qMsEMtghYpzecJw58Td8xB2fG/RxXu',password_change_required=0 WHERE id="+accountId);
    const headers=async()=>{
      const token=(await client.storageState()).cookies.find(c=>c.name==='XSRF-TOKEN');
      assert.ok(token);
      return {'X-XSRF-TOKEN':decodeURIComponent(token.value)};
    };
    await client.get('/signin');
    const logged=await client.post('/api/auth/login',{headers:await headers(),form:{loginId:login,password:'Local-Demo-Init!26'}});
    assert.equal(logged.status(),200,await logged.text());
    const actor=await (await client.get('/api/me')).json();
    assert.ok(!actor.features.some(f=>f.startsWith('ADMIN_')));
    assert.equal((await client.get('/api/admin/personnel')).status(),403);
    const doc=await (await client.get('/api/approvals/docs/'+docId)).json();
    const approval=await client.post('/api/approvals/docs/'+docId+'/approve',{headers:await headers(),data:{version:doc.version}});
    assert.equal(approval.status(),204,await approval.text());
    const approved=await (await page.request.get(origin+'/api/work/requests/'+pending.id+'?from=mine')).json();
    assert.equal(approved.phase,'SENT');
    assert.equal(approved.items.length,1);

    const ownOptions=await (await client.get('/api/work/types/own')).json();
    const ordinaryType=ownOptions.find(t=>t.isOther);
    assert.ok(ordinaryType);
    const own=await client.post('/api/work/requests',{headers:await headers(),data:{
      ...registration,title:title+'일반사용자',assigneePersonnelIds:[Number(personnelId)],
      targets:[{departmentId:actor.departmentId,types:[{workTypeId:ordinaryType.typeId}]}],
      viewers:{personnelIds:[Number(personnelId)]},steps:[{kind:'APPROVE',accountId:actor.accountId}],
    }});
    assert.equal(own.status(),201,await own.text());
    ordinary=await own.json();
  } finally {
    await client.dispose();
    sql("UPDATE account SET password_hash='"+oldHash+"',password_change_required="+required+" WHERE id="+accountId);
  }
  fs.writeFileSync(path.join(evidence,'registration-result.json'),JSON.stringify({
    uiRequestId:created.id,pendingRequestId:pending.id,ordinaryRequestId:ordinary.id,
    listReturn:list,uiSubmission:true,creatorCannotApproveAnotherPersonsTurn:true,assignedApproval:true,ordinaryRegistration:true,
  },null,2));
  console.log('PASS registration UI submit, filtered-list return/reload, pending approval, unauthorized approval rejection, assigned approval and ordinary-user registration');
};
