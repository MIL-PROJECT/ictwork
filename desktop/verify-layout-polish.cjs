const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

async function ready(page) {
  await page.locator('.status-content[aria-busy=false]').waitFor();
  await page.locator('main a[href^="/tasks?tab=department"], .status-content table').first().waitFor();
}
async function geometry(page) {
  return page.evaluate(() => {
    const rect = selector => {
      const {x,y,width,height,bottom} = document.querySelector(selector).getBoundingClientRect();
      return {x,y,width,height,bottom};
    };
    return {panel:rect('#status-panel'),content:rect('.status-content'),root:rect('.work-status'),
      overflow:document.documentElement.scrollWidth-document.documentElement.clientWidth,
      modal:!!document.querySelector('#status-panel[aria-modal=true]')};
  });
}
function fullWidth(g) {
  assert.ok(g.panel.bottom <= g.content.y+1, 'Conditions must precede statistics vertically');
  assert.ok(Math.abs(g.panel.width-g.content.width)<2, 'Conditions and statistics must use the same full width');
  assert.ok(Math.abs(g.root.width-g.content.width)<2);
  assert.ok(g.overflow<=1, 'No horizontal page overflow');
  assert.equal(g.modal,false, 'Conditions must remain inline on mobile');
}

// Uses the signed-in actor's real scope, with no permission or API fixtures.
async function checkScope(page,origin,evidence,user) {
  const response=await page.request.get(origin+'/api/work-status/scope');
  assert.equal(response.status(),200);
  const scope=await response.json();
  await page.goto(origin+'/status');
  await page.locator('.work-status').waitFor();
  if(!scope.showTree&&scope.departmentId===null) {
    assert.equal(await page.locator('#status-panel').count(),0);
    return {user,showTree:false,noDepartment:true};
  }
  await ready(page);
  assert.equal(await page.locator('#status-panel [role=tree]').count(),scope.showTree?1:0);
  fullWidth(await geometry(page));
  if(scope.showTree) {
    const candidates=page.locator('#status-panel [role=treeitem][aria-checked=false]');
    if(await candidates.count()) {
      const [changed]=await Promise.all([
        page.waitForResponse(r=>new URL(r.url()).pathname==='/api/work-status'&&r.ok()),
        candidates.first().press('Space'),
      ]);
      const data=await changed.json();
      assert.ok(data.departmentIds.every(id=>scope.nodes.some(node=>node.id===id)));
      await ready(page);
    }
    const separate=page.getByRole('radio',{name:'부서별',exact:true});
    if(await separate.isEnabled()) {
      const [changed]=await Promise.all([
        page.waitForResponse(r=>new URL(r.url()).pathname==='/api/work-status'&&r.ok()), separate.click(),
      ]);
      assert.equal((await changed.json()).view,'SEPARATE');
      await page.getByRole('table').first().waitFor();
      await ready(page);
      await page.getByRole('radio',{name:'통합',exact:true}).click();await ready(page);
    }
    await page.getByRole('searchbox',{name:'부서 찾기',exact:true}).fill('없는부서-검증');
    assert.equal(await page.locator('#status-panel [role=treeitem]').count(),0);
    await page.getByRole('searchbox',{name:'부서 찾기',exact:true}).fill('');
    assert.ok(await page.locator('#status-panel [role=treeitem]').count());
  }
  await page.screenshot({path:path.join(evidence,'status-role-'+user+'.png'),animations:'disabled'});
  return {user,showTree:scope.showTree,scopeAndComparisonPreserved:true};
}

module.exports = async function verifyLayoutPolish(page,origin,evidence) {
  const checks=[];const layouts=[];
  await page.setViewportSize({width:1600,height:900});
  const scope=await checkScope(page,origin,evidence,'default-demo');
  checks.push('real scope visibility, department selection, comparison and tree search');
  const toggle=page.getByRole('button',{name:'보기 조건',exact:true});
  const before=page.url();
  const dataBefore=await page.locator('.status-content').innerText();
  await page.locator('#status-panel').getByRole('button',{name:'닫기',exact:true}).click();
  assert.equal(await page.locator('#status-panel').count(),0);
  assert.equal(await toggle.getAttribute('aria-expanded'),'false');
  assert.equal(await toggle.evaluate(el=>el===document.activeElement),true);
  assert.equal(page.url(),before);
  assert.equal(await page.locator('.status-content').innerText(),dataBefore);
  await toggle.click();await page.locator('#status-panel').waitFor();
  const week=page.getByRole('radio',{name:'이번 주',exact:true});
  const [weekly]=await Promise.all([page.waitForResponse(r=>new URL(r.url()).pathname==='/api/work-status'&&r.ok()),week.click()]);
  assert.equal(new URL(weekly.url()).searchParams.get('period'),'WEEK');await ready(page);
  assert.equal(await week.getAttribute('aria-checked'),'true');
  await page.reload();await ready(page);
  assert.equal(await week.getAttribute('aria-checked'),'true');
  await Promise.all([
    page.waitForResponse(r=>new URL(r.url()).pathname==='/api/work-status'&&new URL(r.url()).searchParams.get('period')==='CUSTOM'&&r.ok()),
    page.getByRole('radio',{name:'직접 지정',exact:true}).click(),
  ]);
  await ready(page);
  const start=page.locator('#status-panel').getByLabel('시작일',{exact:true});
  const end=page.locator('#status-panel').getByLabel('종료일',{exact:true});
  const validUrl=page.url();
  const reads=[];const read=r=>{if(new URL(r.url()).pathname==='/api/work-status')reads.push(r.url());};
  page.on('request',read);
  try {
    await start.fill('2026-12-01');await end.fill('2026-11-01');await end.blur();
    await page.waitForTimeout(850);
    assert.equal(await start.getAttribute('aria-invalid'),'true');
    assert.equal(page.url(),validUrl);assert.equal(reads.length,0,'Invalid period must not reach API: '+JSON.stringify(reads));
    // Correct the end first so the draft stays invalid until the final edit.
    await end.fill('2026-11-08');
    const [custom]=await Promise.all([page.waitForResponse(r=>{
      const u=new URL(r.url());return u.pathname==='/api/work-status'&&u.searchParams.get('from')==='2026-11-01'&&u.searchParams.get('to')==='2026-11-08'&&r.ok();
    }),start.fill('2026-11-01').then(()=>start.blur())]);
    const query=new URL(custom.url()).searchParams;
    assert.equal(query.get('from'),'2026-11-01');assert.equal(query.get('to'),'2026-11-08');
    await ready(page);
  } finally { page.off('request',read); }
  checks.push('collapse/focus/query/data preservation, week reload and custom date validation/debounce');
  const [exported]=await Promise.all([
    page.waitForResponse(r=>new URL(r.url()).pathname==='/api/work-status/export'),
    page.getByRole('button',{name:'엑셀',exact:true}).click(),
  ]);
  assert.equal(exported.status(),200);
  const excelPath=path.join(evidence,'statistics.xlsx');
  const deadline=Date.now()+15000;
  while((!fs.existsSync(excelPath)||fs.statSync(excelPath).size===0)&&Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,100));
  assert.ok(fs.existsSync(excelPath),'The real UI export must save a file');
  assert.equal(fs.readFileSync(excelPath).subarray(0,2).toString(),'PK');
  await page.evaluate(()=>{window.__savedPrint=window.print;window.print=()=>{window.__printCalled=true;};});
  try {
    await page.getByRole('button',{name:'인쇄·PDF',exact:true}).click();
    await page.waitForFunction(()=>window.__printCalled===true);
    await page.emulateMedia({media:'print'});
    assert.equal(await page.locator('#status-panel').isVisible(),false);
    await page.screenshot({path:path.join(evidence,'statistics-print.png'),fullPage:true,animations:'disabled'});
  } finally {
    await page.emulateMedia({media:'screen'});
    await page.evaluate(()=>{window.print=window.__savedPrint;delete window.__savedPrint;delete window.__printCalled;window.dispatchEvent(new Event('afterprint'));});
  }
  checks.push('real XLSX export, print invocation and print-only layout (system print dialog not opened)');
  await page.getByRole('radio',{name:'이번 달',exact:true}).click();await ready(page);
  for(const [width,height] of [[1600,900],[1366,768],[1024,768],[390,844]]) {
    await page.setViewportSize({width,height});await page.waitForTimeout(250);
    const g=await geometry(page);fullWidth(g);layouts.push({width,height,...g});
    await page.screenshot({path:path.join(evidence,`statistics-top-${width}.png`),animations:'disabled'});
  }
  await page.evaluate(()=>{localStorage.setItem('language','en');localStorage.setItem('i18nextLng','en');localStorage.setItem('theme','dark');});
  await page.reload();await ready(page);fullWidth(await geometry(page));
  await page.screenshot({path:path.join(evidence,'statistics-top-english-dark-mobile.png'),animations:'disabled'});
  await page.evaluate(()=>{localStorage.setItem('language','ko');localStorage.setItem('i18nextLng','ko');localStorage.setItem('theme','light');});
  await page.setViewportSize({width:1600,height:900});await page.goto(origin+'/board');
  await page.locator('.board-attention:not([data-state=loading])').waitFor();
  assert.equal(await page.locator('.board-eyebrow').count(),0);
  const style=await page.locator('.board-attention').evaluate(el=>{const s=getComputedStyle(el);return {left:s.borderLeftWidth,top:s.borderTopWidth,radius:s.borderRadius};});
  assert.equal(style.left,style.top,'No accented left border');assert.equal(style.left,'1px');
  assert.equal(await page.locator('.board-core-indicator').first().evaluate(el=>getComputedStyle(el).borderLeftWidth),'0px');
  await page.screenshot({path:path.join(evidence,'board-refined-band.png'),animations:'disabled'});
  checks.push('four responsive widths, English/dark, removed eyebrow and neutral rounded risk band');
  const link=page.locator('.board-risk-row').first();
  if(await link.count()) {
    const href=await link.getAttribute('href');await link.click();await page.waitForURL(origin+href);
    await page.getByRole('dialog').waitFor();await page.goto(origin+'/board');
    await page.locator('.board-attention:not([data-state=loading])').waitFor();
  }
  fs.writeFileSync(path.join(evidence,'layout-polish-result.json'),JSON.stringify({success:true,checks,scope,layouts,printDialogEmulated:true},null,2));
  await page.setViewportSize({width:1440,height:950});
  console.log('PASS top conditions/full-width statistics, scope, period/query, XLSX/print, responsive and refined board');
};
module.exports.checkScope=checkScope;
