const assert=require('node:assert/strict');const fs=require('node:fs');const path=require('node:path');
const {seedOperations,catalog,PREFIX}=require('./seed-operations-demo.cjs');
module.exports=async function verifyDemoSeed(page,origin,evidence) {
  const request=async(method,url,data)=>{
    const token=(await page.context().cookies(origin)).find(cookie=>cookie.name==='XSRF-TOKEN');
    const response=await page.request.fetch(origin+url,{method,data,headers:token?{'X-XSRF-TOKEN':decodeURIComponent(token.value)}:{}});
    return {status:response.status(),data:await response.json().catch(()=>null)};
  };
  const before=(await request('GET','/api/operations')).data.items;
  assert.equal(before.filter(item=>item.code.startsWith(PREFIX)).length,0,'A fresh synthetic dataset is required');
  const plan=await seedOperations({request,dryRun:true});assert.equal(plan.toCreate.length,38);
  const first=await seedOperations({request,manifestPath:path.join(evidence,'seed-first.json')});
  assert.equal(first.created.length,38);assert.deepEqual(first.counts,{NORMAL:30,WATCH:4,INSPECTION:2,FAULT:1,OFFLINE:1});
  const second=await seedOperations({request,manifestPath:path.join(evidence,'seed-repeat.json')});
  assert.equal(second.created.length,0);assert.equal(second.skipped.length,38);
  assert.deepEqual(second.seeded,first.seeded,'Repeated seeding must preserve state, availability, versions and editor timestamps');
  for(const row of catalog.slice(0,3)) {
    const item=first.seeded.find(item=>item.code===row.code);const detail=(await request('GET','/api/operations/'+item.id)).data;
    assert.equal(detail.history.length,3);assert.equal(detail.item.status,row.status);assert.equal(detail.item.uptimePct,row.uptimePct);
  }
  const after=(await request('GET','/api/operations')).data.items;
  assert.equal(after.length,before.length+38);
  await page.goto(origin+'/operations');await page.locator('.ops-table tbody tr').first().waitFor();
  await page.getByRole('searchbox').fill(PREFIX);assert.equal(await page.locator('.ops-table tbody tr').count(),10);
  await page.locator('.ops-paging').getByRole('button',{name:'다음',exact:true}).click();
  assert.equal(new URL(page.url()).searchParams.get('page'),'2');
  await page.screenshot({path:path.join(evidence,'demo-operations-page2.png'),animations:'disabled'});
  await page.goto(origin+'/board');await page.waitForFunction(count=>document.querySelector('.board-metric-value')?.textContent===count+'개',after.length);
  assert.equal((await page.locator('.board-metric-value').allTextContents())[1],after.filter(item=>item.status==='NORMAL').length+'개');
  fs.writeFileSync(path.join(evidence,'demo-seed-result.json'),JSON.stringify({success:true,datasetSystems:38,totalIncludingExisting:after.length,existingUnchanged:before.length,counts:first.counts,repeatCreated:0,actualApiHistory:true},null,2));
  console.log('PASS 38 real API demo systems, five states, current-rate history, zero duplicates and preserved existing records');
};
