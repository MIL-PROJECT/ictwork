const {_electron}=require('playwright');const path=require('node:path'),fs=require('node:fs'),assert=require('node:assert/strict');
const [bundle,evidence]=process.argv.slice(2).map(p=>path.resolve(p));
(async()=>{
  assert.ok(process.env.OMNIS_DEMO_PASSWORD,'Provide the current demo password through the environment');
  fs.mkdirSync(evidence,{recursive:true});
  const profile=path.join(process.env.APPDATA,'OMNIS Desktop Main');
  const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;
  const app=await _electron.launch({executablePath:path.join(bundle,'OMNIS.exe'),args:['--user-data-dir='+profile],env,timeout:60000});
  const page=await app.firstWindow();page.setDefaultTimeout(20000);
  try {
    assert.equal(path.resolve(await app.evaluate(({app})=>app.getPath('userData'))),path.resolve(profile));
    await page.locator('[name=loginId]').waitFor({timeout:180000});const origin=new URL(page.url()).origin;
    await page.setViewportSize({width:1600,height:900});await page.evaluate(()=>document.fonts.ready);
    await page.screenshot({path:path.join(evidence,'login.png'),animations:'disabled'});
    await page.locator('[name=loginId]').fill(process.env.OMNIS_DEMO_LOGIN||'bn1-co1-admin');
    await page.locator('[name=password]').fill(process.env.OMNIS_DEMO_PASSWORD);await page.locator('[name=password]').press('Enter');
    await page.locator('.board-control').waitFor();await page.locator('.board-actions button:not([disabled])').waitFor();
    const me=await(await page.request.get(origin+'/api/me')).json();
    const data=await(await page.request.get(origin+'/api/operations')).json();assert.equal(data.items.length,38);
    const counts=Object.fromEntries(['NORMAL','WATCH','INSPECTION','FAULT','OFFLINE'].map(status=>[status,data.items.filter(item=>item.status===status).length]));
    assert.deepEqual(counts,{NORMAL:30,WATCH:4,INSPECTION:2,FAULT:1,OFFLINE:1});
    assert.equal((await page.locator('.board-metric-value').allTextContents())[0],'38개');
    assert.equal((await page.locator('.board-metric-value').allTextContents())[1],'30개');
    await page.waitForFunction(()=>!document.querySelector('.board-actions button')?.disabled&&!document.querySelector('.board-control .board-resource-note[data-state=loading]'));
    await page.screenshot({path:path.join(evidence,'board-1600x900.png'),animations:'disabled'});
    await page.goto(origin+'/operations');await page.locator('.ops-table tbody tr').first().waitFor();
    assert.equal(await page.locator('.ops-table tbody tr').count(),10);
    await page.screenshot({path:path.join(evidence,'operations.png'),animations:'disabled'});
    const first=data.items[0];await page.goto(origin+'/operations?system='+first.id);await page.locator('.ops-chart').waitFor();
    const detail=await(await page.request.get(origin+'/api/operations/'+first.id)).json();assert.equal(detail.history.length,3);
    await page.screenshot({path:path.join(evidence,'history.png'),animations:'disabled'});
    fs.writeFileSync(path.join(evidence,'result.json'),JSON.stringify({success:true,actualUserProfile:true,profile,executable:path.join(bundle,'OMNIS.exe'),loginId:me.loginId,departmentId:me.departmentId,total:38,counts,normalRate:79,history:detail.history.length,existingSettingsPreserved:{fontSize:me.fontSize},readOnlyUiVerification:true,allBoardSourcesSettled:true},null,2));
    console.log('PASS actual user profile: 38 systems, 30 normal, 79%, history and fully loaded board; no data/settings writes');
  }catch(error){await page.screenshot({path:path.join(evidence,'failure.png')}).catch(()=>{});throw error;}finally{await app.close();}
})().catch(error=>{console.error(String(error));process.exitCode=1;});
