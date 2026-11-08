const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');

module.exports=async function verifyOperationsFailures(page,origin,evidence) {
  const list=await (await page.request.get(origin+'/api/operations')).json();
  const item=list.items.find(item=>item.canUpdateStatus);assert.ok(item,'Use the owned synthetic admin profile');
  const endpoint='**/api/operations';let state=503;
  const fail=route=>route.fulfill({status:state,json:{message:'합성 운영현황 조회 실패'}});
  try {
    await page.route(endpoint,fail);
    for(const status of [503,403]) {
      state=status;await page.goto(origin+'/operations');
      await page.getByRole('alert').filter({hasText:'합성 운영현황 조회 실패'}).waitFor();
      assert.equal(await page.locator('.ops-table tbody tr').count(),0,'Do not retain an earlier readable list after a failed new read');
    }
    await page.unroute(endpoint,fail);await page.getByRole('button',{name:'다시 시도',exact:true}).click();
    await page.locator('.ops-table tbody tr').first().waitFor();
    await page.goto(origin+'/operations?system='+item.id);
    await page.getByRole('button',{name:'상태·운용률 변경',exact:true}).click();
    const mutation='**/api/operations/'+item.id+'/status';let calls=0;
    const hang=async route=>{calls++;await new Promise(resolve=>setTimeout(resolve,16000));await route.abort().catch(()=>{});};
    await page.route(mutation,hang);
    try {
      const submit=page.getByRole('dialog').getByRole('button',{name:'저장',exact:true});
      await submit.click();assert.equal(await submit.isDisabled(),true);
      await page.getByRole('alert').filter({hasText:'응답 시간이 초과됐습니다.'}).waitFor({timeout:20000});
      assert.equal(await submit.isDisabled(),false);assert.equal(calls,1,'Uncertain writes must not retry automatically');
      await page.screenshot({path:path.join(evidence,'operations-save-timeout.png'),animations:'disabled'});
      await page.getByRole('button',{name:'취소',exact:true}).click();
    } finally {await page.unroute(mutation,hang);}
    const after=await (await page.request.get(origin+'/api/operations/'+item.id)).json();
    assert.equal(after.item.version,item.version,'The intercepted write was never forwarded to the DB');
    await page.evaluate(()=>{localStorage.setItem('language','en');localStorage.setItem('i18nextLng','en');localStorage.setItem('theme','dark');});
    await page.goto(origin+'/operations?system='+item.id);await page.locator('.ops-chart').waitFor();
    await page.locator('html[lang=en].dark').waitFor({state:'attached'});
    await page.evaluate(()=>document.documentElement.dataset.fontSize='large');await page.waitForTimeout(200);
    const bounds=await page.getByRole('dialog').boundingBox();const viewport=page.viewportSize();
    // Electron may use its native initial size instead of a Playwright viewport.
    const size=viewport ?? await page.evaluate(()=>({width:innerWidth,height:innerHeight}));
    assert.ok(bounds&&bounds.x>=0&&bounds.y>=0&&bounds.x+bounds.width<=size.width+1&&bounds.y+bounds.height<=size.height+1);
    await page.screenshot({path:path.join(evidence,'operations-english-dark-large.png'),animations:'disabled'});
    fs.writeFileSync(path.join(evidence,'operations-failures-result.json'),JSON.stringify({success:true,readFailures:[503,403],retryRecovery:true,writeTimeoutMs:15000,automaticWriteRetries:0,interceptedWriteChangedDb:false,englishDarkLargeDialog:true},null,2));
  } finally {
    await page.unroute(endpoint,fail);
    await page.evaluate(()=>{localStorage.setItem('language','ko');localStorage.setItem('i18nextLng','ko');localStorage.setItem('theme','light');delete document.documentElement.dataset.fontSize;});
  }
  console.log('PASS operations read failure/retry, bounded uncertain write without automatic retry, unchanged DB, English/dark/large-font dialog');
};
