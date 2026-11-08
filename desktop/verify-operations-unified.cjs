const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');

// Mutating checks run only inside the isolated setup-smoke / role-test profile.
async function checkActor(page,origin,evidence,user,{mutate=false}={}) {
  const checks=[];
  const raw=await(await page.request.get(origin+'/api/ict-status')).json();
  const operations=await(await page.request.get(origin+'/api/operations')).json();
  const aside=page.locator('aside').first();
  const nav=page.getByRole('navigation',{name:'운영현황',exact:true});
  await page.goto(origin+'/operations');await page.locator('.ops-paging').waitFor();
  assert.equal(await aside.getByRole('link',{name:'운영현황',exact:true}).count(),1);
  assert.equal(await aside.getByRole('link',{name:'정보통신 현황',exact:true}).count(),0);
  assert.equal(await nav.getByRole('link').count(),2);
  assert.equal(await nav.getByRole('link',{name:'체계 운용',exact:true}).getAttribute('aria-current'),'page');
  assert.equal(await page.getByRole('button',{name:'체계 등록',exact:true}).count(),operations.createDepartments.length?1:0);
  const search=page.getByRole('searchbox');
  await search.fill('DEMO-OMNIS');
  await nav.getByRole('link',{name:'통신·경보',exact:true}).click();
  await page.waitForURL(url=>url.pathname==='/operations'&&url.searchParams.get('view')==='signals');
  await nav.getByRole('link',{name:'통신·경보',exact:true}).locator('xpath=self::*[@aria-current="page"]').waitFor();
  await page.getByRole('heading',{level:1,name:'운영현황',exact:true}).waitFor();
  assert.equal(new URL(page.url()).searchParams.get('q'),'DEMO-OMNIS');
  assert.equal(await nav.getByRole('link',{name:'통신·경보',exact:true}).getAttribute('aria-current'),'page');
  await page.waitForFunction(()=>!!document.querySelector('main ul')||!!document.querySelector('main h2')||!!document.querySelector('main button'));
  // Wait for the permission-dependent action, not just the workspace heading.
  if(raw.addDepartments.length)await page.getByRole('button',{name:'박스 추가',exact:true}).waitFor();
  else await page.waitForTimeout(150);
  assert.equal(await page.getByRole('button',{name:'박스 추가',exact:true}).count(),raw.addDepartments.length?1:0);
  const visible=raw.boxes.filter(b=>!b.hidden);
  for(const title of new Set(raw.boxes.map(b=>b.title))) {
    const expected=visible.filter(b=>b.title===title).length;
    const cards=page.getByTitle(title+' 상세 열기',{exact:true});
    if(expected)await cards.first().waitFor();
    assert.equal(await cards.count(),expected,'Visible/hidden boxes remain correct even when departments share a title');
  }
  await page.screenshot({path:path.join(evidence,'unified-signals-'+user+'.png'),animations:'disabled'});
  await nav.getByRole('link',{name:'체계 운용',exact:true}).click();await page.locator('.ops-paging').waitFor();
  assert.equal(await page.getByRole('searchbox').inputValue(),'DEMO-OMNIS');
  checks.push('one sidebar entry, two explicit views, real role actions/visible boxes and system query preserved');
  const target=visible[0];
  if(target) {
    const detail=await(await page.request.get(origin+'/api/ict-boxes/'+target.id)).json();
    await page.goto(origin+`/ict-status?box=${target.id}&tab=revisions#saved-link`);
    await page.getByRole('dialog').waitFor();
    const url=new URL(page.url());assert.equal(url.pathname,'/operations');assert.equal(url.searchParams.get('view'),'signals');
    assert.equal(url.searchParams.get('box'),String(target.id));assert.equal(url.searchParams.get('tab'),'revisions');assert.equal(url.hash,'#saved-link');
    await page.reload();await page.getByRole('dialog').waitFor();
    await page.getByRole('dialog').getByRole('tab',{name:'내용',exact:true}).click();
    await page.getByRole('dialog').getByRole('tab',{name:'기록',exact:true}).waitFor();
    if(detail.canEdit)await page.getByRole('button',{name:'고치기',exact:true}).waitFor();
    assert.equal(await page.getByRole('button',{name:'고치기',exact:true}).count(),detail.canEdit?1:0);
    assert.equal(await page.getByRole('button',{name:'박스 지우기',exact:true}).count(),detail.canManage?1:0);
    await page.keyboard.press('Escape');await page.getByRole('dialog').waitFor({state:'hidden'});
    assert.equal(new URL(page.url()).searchParams.get('view'),'signals');
    assert.equal(new URL(page.url()).searchParams.has('box'),false);
    checks.push('legacy link/reload/history/content, detail ACL and close stays in communication view');
  }
  let createdId;
  if(mutate&&raw.addDepartments.length) {
    const title='QA 운영 통합 '+Date.now();
    await page.goto(origin+'/operations?view=signals');await page.getByRole('button',{name:'박스 추가',exact:true}).click();
    const dialog=page.getByRole('dialog');await dialog.waitFor();
    await dialog.locator('#add-box-title').fill(title);await dialog.locator('#add-box-summary').fill('정상 연동');await dialog.locator('#add-box-color').selectOption('GREEN');
    const [created]=await Promise.all([page.waitForResponse(r=>new URL(r.url()).pathname==='/api/ict-boxes'&&r.request().method()==='POST'),dialog.getByRole('button',{name:'추가',exact:true}).click()]);
    assert.equal(created.status(),201);createdId=(await created.json()).id;
    await page.getByRole('dialog').getByText(title,{exact:true}).first().waitFor();
    await page.keyboard.press('Escape');await page.getByRole('dialog').waitFor({state:'hidden'});
    const menu=page.getByRole('button',{name:title+' 박스 메뉴',exact:true});await menu.click();
    const [hiddenSave]=await Promise.all([
      page.waitForResponse(r=>new URL(r.url()).pathname==='/api/ict-status/layout/'+createdId&&r.request().method()==='PUT'),
      page.getByRole('menuitem',{name:'숨기기',exact:true}).click(),
    ]);assert.ok(hiddenSave.ok());
    await page.getByTitle(title+' 상세 열기',{exact:true}).waitFor({state:'hidden'});
    const token=(await page.context().cookies(origin)).find(c=>c.name==='XSRF-TOKEN');
    const hidden=await(await page.request.get(origin+'/api/ict-status')).json();assert.equal(hidden.boxes.find(b=>b.id===createdId).hidden,true);
    await page.getByRole('button',{name:/^숨긴 박스/}).click();
    const row=page.getByRole('dialog').getByRole('listitem').filter({hasText:title});
    const [shownSave]=await Promise.all([
      page.waitForResponse(r=>new URL(r.url()).pathname==='/api/ict-status/layout/'+createdId&&r.request().method()==='PUT'),
      row.getByRole('button',{name:'다시 보이기',exact:true}).click(),
    ]);assert.ok(shownSave.ok());
    await row.waitFor({state:'hidden'});await page.keyboard.press('Escape');
    await page.getByTitle(title+' 상세 열기',{exact:true}).waitFor();
    await page.reload();await page.getByTitle(title+' 상세 열기',{exact:true}).waitFor();
    const restored=await(await page.request.get(origin+'/api/ict-status')).json();assert.equal(restored.boxes.find(b=>b.id===createdId).hidden,false);
    // Delete only the fixture this test just created, never an existing demo box.
    const removed=await page.request.delete(origin+'/api/ict-boxes/'+createdId,{headers:{'X-XSRF-TOKEN':decodeURIComponent(token.value)}});assert.equal(removed.status(),204);
    checks.push('real UI box creation, personal hide/show, reload persistence and cleanup of owned fixture');
  }
  await page.goto(origin+'/board');await page.locator('.board-attention:not([data-state=loading])').waitFor();
  assert.equal(await page.locator('.board-risk-zone .board-band-value').count(),0);
  assert.equal(await page.locator('.board-risk-zone a[href="/duty"]').count(),0);
  assert.equal(await page.locator('.board-risk-zone a[href*="overdue"]').count(),0);
  await page.locator('.board-actions button:not([disabled])').waitFor();
  const centered=await page.locator('.board-receipt-columns').evaluateAll(elements=>elements.flatMap(el=>{
    const r=el.getBoundingClientRect();return [...el.children].filter(c=>getComputedStyle(c).display!=='none').map(c=>{const x=c.getBoundingClientRect();return Math.abs((x.top+x.bottom)/2-(r.top+r.bottom)/2);});
  }));assert.ok(centered.every(d=>d<1),'All visible receipt headers must be vertically centered');
  const core=await page.locator('.board-core').boundingBox(),attention=await page.locator('.board-attention').boundingBox();
  assert.ok(Math.abs(core.y-attention.y)<1&&core.x+core.width<=attention.x,'Control and attention are separate adjacent cards');
  await page.screenshot({path:path.join(evidence,'unified-board-'+user+'.png'),animations:'disabled'});
  checks.push('risk area only contains control/alerts, separate cards and centered receipt headers');
  return {user,checks,visibleBoxes:visible.length,canCreateBoxes:!!raw.addDepartments.length,createdId,receiptHeaderCenterOffsets:centered};
}
module.exports=async(page,origin,evidence)=>{
  await page.setViewportSize({width:1600,height:900});
  const result=await checkActor(page,origin,evidence,'default-demo',{mutate:true});
  fs.writeFileSync(path.join(evidence,'operations-unified-result.json'),JSON.stringify({success:true,result,actualApi:true},null,2));
  await page.setViewportSize({width:1440,height:950});
  console.log('PASS unified operations menu/views, legacy detail/reload/ACL, real box creation/layout and focused risk cards');
};
module.exports.checkActor=checkActor;
