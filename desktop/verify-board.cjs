const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// Run only against the isolated profile owned by setup-smoke.cjs. Intercepts below
// are presentation/failure fixtures, separate from the real API checks first.
module.exports = async function verifyBoard(page, origin, evidence) {
  const checks = [];
  const scopeRoute='**/api/work-status/scope';
  const boardEndpoints=/\/api\/(ict-status|work-status(?:\/scope)?|work\/items|work\/receipts|directives|duty\/today)(?:\?.*)?$/;
  const count = page.getByTestId('attention-count');
  const attention = page.locator('.board-attention');
  const refresh = async () => {
    await page.locator('.board-actions button:not([disabled])').waitFor();
    await page.locator('.board-actions button').click();
  };
  const go = async () => {
    await page.goto(origin+'/board');
    await page.locator('.board-attention').waitFor();
  };
  const ready = async state => page.locator(`.board-attention[data-state="${state}"]`).waitFor();
  const shot = async name => { await page.waitForTimeout(150); await page.screenshot({path:path.join(evidence,name),animations:'disabled'}); };

  await go();
  await ready('ready');
  const response = await page.request.get(origin+'/api/ict-status');
  assert.equal(response.status(),200);
  const actual = await response.json();
  const visible = actual.boxes.filter(box=>!box.hidden);
  const actualAlerts = visible.filter(box=>['RED','ORANGE','YELLOW'].includes(box.shownColor));
  assert.equal(await count.innerText(),String(new Set(actualAlerts.map(box=>box.id)).size));
  assert.equal(await page.getByText('내 부서 접수 대기',{exact:true}).count(),0);
  assert.equal(await page.locator('main').getByText('내 결재 차례',{exact:true}).count(),0);
  const realTarget=actualAlerts[0]||visible[0];
  if(realTarget) {
    await page.goto(origin+'/ict-status?box='+realTarget.id);
    await page.getByRole('dialog').waitFor();
    assert.equal(new URL(page.url()).searchParams.get('box'),String(realTarget.id));
    await go();await ready('ready');
  }
  await shot('board-real-data.png');
  await page.screenshot({path:path.join(evidence,'board-real-data-full.png'),fullPage:true,animations:'disabled'});
  const realMetric=page.locator('.board-metric[href]').first();
  const href=await realMetric.getAttribute('href');
  assert.ok(href.includes('tab=department')&&href.includes('d=')&&href.includes('closedFrom=')&&href.includes('closedTo='));
  await realMetric.click();await page.waitForURL(origin+href);await go();await ready('ready');
  checks.push('real backend attention count, hidden scope, box detail and work-filter navigation');

  const base=visible[0];assert.ok(base,'The synthetic demo must include an ICT box');
  const fixture=(id,color,title,extra={})=>({...base,id,shownColor:color,title,shownSummary:'현재 상태 '+id,hidden:false,updatedAt:'2026-11-08T09:00:00',...extra});
  const fixtures=[
    fixture(900001,'YELLOW','통신 상태'),fixture(900002,'ORANGE','전장망'),
    fixture(900003,'RED','GPS 전파교란 · 장시간 확인이 필요한 긴 제목과 상세 설명을 가진 운용 상태'),
    fixture(900004,'RED','관측망 장애',{updatedAt:'2026-11-08T10:00:00'}),fixture(900005,'ORANGE','회선 제한'),
    fixture(900006,'GREEN','CPCON',{shownSummary:'5단계',departmentLabel:'1중대'}),
    fixture(900007,'BLUE','위기경보단계',{shownSummary:'관심'}),
    fixture(900008,'GREEN','CPCON',{departmentId:999,departmentLabel:'2중대',shownSummary:'4단계'}),
    fixture(900009,'RED','숨긴 위험 항목',{hidden:true}),
  ];
  fixtures.push({...fixtures[0]});
  const endpoint='**/api/ict-status';
  let mode='fixture';
  const intercept=async route=>{
    if(mode==='fail') return route.fulfill({status:503,json:{message:'Synthetic source failure'}});
    if(mode==='denied') return route.fulfill({status:403,json:{message:'Synthetic revoked access'}});
    if(mode==='timeout') { await new Promise(resolve=>setTimeout(resolve,16000)); return route.abort().catch(()=>{}); }
    return route.fulfill({json:{...actual,boxes:mode==='empty'?[]:fixtures,addDepartments:[]}});
  };
  await page.route(endpoint,intercept);
  try {
    await go();await ready('ready');
    assert.equal(await count.innerText(),'5');
    assert.equal(await page.locator('.board-core-indicator').count(),2);
    assert.equal(await page.locator('.board-risk-row').count(),2);
    assert.ok((await page.locator('.board-risk-row').first().innerText()).includes('관측망 장애'));
    assert.equal(await page.getByText('숨긴 위험 항목',{exact:true}).count(),0);
    const allAlerts=page.getByRole('button',{name:'주의 전체 5건 보기',exact:true});
    const beforeHeight=await page.evaluate(()=>document.documentElement.scrollHeight);
    await allAlerts.click();await page.getByRole('dialog').waitFor();
    assert.equal(await page.locator('.board-dialog-box').count(),5);
    await page.keyboard.press('Escape');await page.getByRole('dialog').waitFor({state:'hidden'});
    assert.equal(await allAlerts.evaluate(element=>element===document.activeElement),true,'Return focus to the opener');
    assert.equal(await page.evaluate(()=>document.documentElement.scrollHeight),beforeHeight,'Details must not expand the board');
    await page.getByRole('button',{name:'핵심 운용 상태 전체 3개 보기',exact:true}).click();
    await page.getByRole('dialog').waitFor();assert.equal(await page.locator('.board-dialog-box').count(),3);
    await page.keyboard.press('Escape');
    checks.push('priority sorting, deduplication, hidden exclusion, two type highlights and all departments/alerts in dialogs');
    const layouts=await require('./verify-board-fit.cjs')(page,evidence);
    const failureLayouts=[];
    const checkFailureLayout=async name=>{
      await page.setViewportSize({width:1366,height:768});
      await page.evaluate(()=>document.documentElement.dataset.fontSize='large');
      await page.locator('header').getByRole('button',{name:'AI 업무지원',exact:true}).click();await page.locator('#assist-panel').waitFor();
      const result=await require('./verify-board-fit.cjs').checkLayout(page,evidence,name);failureLayouts.push({name,width:result.width,height:result.height,scrollY:result.scrollY});
      await page.locator('header').getByRole('button',{name:'AI 업무지원',exact:true}).click();await page.locator('#assist-panel').waitFor({state:'hidden'});
      await page.evaluate(()=>delete document.documentElement.dataset.fontSize);await page.setViewportSize({width:1440,height:950});
    };
    mode='fail';await refresh();await ready('stale');
    assert.equal(await count.innerText(),'5');
    assert.ok((await attention.locator('.board-resource-note').getAttribute('title')).includes('이전 확인값'));
    await shot('board-stale-source.png');await checkFailureLayout('stale-large-assist');
    mode='denied';await refresh();await ready('unavailable');
    assert.equal(await count.innerText(),'—');assert.equal(await page.locator('.board-risk-row').count(),0);
    assert.equal(await page.getByText('GPS 전파교란',{exact:true}).count(),0);
    mode='empty';await refresh();await ready('empty');assert.equal(await count.innerText(),'0');
    assert.ok((await attention.innerText()).includes('표시 범위에 빨강·주황·노랑 주의 항목이 없습니다.'));
    await shot('board-no-attention.png');await checkFailureLayout('empty-large-assist');
    mode='fail';await refresh();await ready('stale');
    assert.ok((await attention.innerText()).includes('현재 상태는 재확인이 필요합니다.'));
    await go();await ready('error');assert.equal(await count.innerText(),'—');
    await shot('board-source-unavailable.png');await checkFailureLayout('unverified-large-assist');
    mode='fixture';await refresh();await ready('ready');assert.equal(await count.innerText(),'5');
    mode='timeout';await refresh();await page.locator('.board-attention[data-state=stale]').waitFor({timeout:20000});
    mode='fixture';await refresh();await ready('ready');
    checks.push('partial failure, retained prior time, revoked access clears data, empty vs error, 15s timeout and recovery');

    await page.route(scopeRoute,route=>route.fulfill({status:503,json:{message:'Synthetic scope failure'}}));
    await refresh();await page.locator('.board-work>.board-section-head>.board-resource-note[data-state=error]').waitFor();
    const values=await page.locator('.board-metric-value').allTextContents();
    assert.ok(values.every(value=>value.includes('—')),'Unknown scope must clear previous department figures');
    assert.equal(await count.innerText(),'5');
    await page.unroute(scopeRoute);
    checks.push('unknown work scope clears old figures while ICT remains available');

    const failAll=route=>route.fulfill({status:503,json:{message:'Synthetic total failure'}});
    await page.route(boardEndpoints,failAll);
    await go();await ready('error');assert.equal(await count.innerText(),'—');
    await page.locator('.board-updated.error').waitFor();
    await shot('board-all-sources-failed.png');await checkFailureLayout('all-failed-large-assist');
    await page.unroute(boardEndpoints,failAll);
    mode='fixture';await go();await ready('ready');
    checks.push('all-source failure never shown as normal or zero');

    await page.evaluate(()=>{localStorage.setItem('language','en');localStorage.setItem('i18nextLng','en');localStorage.setItem('theme','dark');});
    await go();await ready('ready');await page.locator('html[lang=en].dark').waitFor({state:'attached'});
    await page.getByRole('heading',{name:'Critical operating status',exact:true}).waitFor();
    await shot('board-english-dark.png');await require('./verify-board-fit.cjs').checkLayout(page,evidence,'english-dark');
    checks.push('English and dark theme');
    fs.writeFileSync(path.join(evidence,'board-result.json'),JSON.stringify({success:true,checks,layouts,failureLayouts,actualVisibleBoxes:visible.length,actualAttention:actualAlerts.length,fixturesAreSynthetic:true},null,2));
  } finally {
    await page.unroute(endpoint,intercept);
    await page.unroute(scopeRoute);
    await page.unroute(boardEndpoints);
    await page.evaluate(()=>{localStorage.setItem('language','ko');localStorage.setItem('i18nextLng','ko');localStorage.setItem('theme','light');delete document.documentElement.dataset.fontSize;});
    await page.setViewportSize({width:1440,height:950});
    await go();await ready('ready');
  }
  console.log('PASS real board/API drill-down, risk sorting/counts, scope, failure/timeout/recovery, responsive/large-font/English/dark/assist');
};
