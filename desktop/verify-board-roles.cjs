const { _electron } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const [installed, evidence] = process.argv.slice(2);

(async()=>{
  const smoke=JSON.parse(fs.readFileSync(path.join(evidence,'installed-smoke-result.json'),'utf8'));
  assert.equal(smoke.success,true,'Only reuse a successfully validated isolated synthetic profile');
  const profile=path.resolve(smoke.profile);
  assert.ok(profile.startsWith(path.resolve(evidence)+path.sep),'Never use the actual user profile');
  const env={...process.env,PATH:path.join(process.env.SystemRoot,'System32'),ICTWORK_AI_ENABLED:'false'};
  delete env.ELECTRON_RUN_AS_NODE;delete env.JAVA_HOME;
  const electron=await _electron.launch({executablePath:path.join(installed,'OMNIS.exe'),args:['--user-data-dir='+profile],env,timeout:60000});
  const page=await electron.firstWindow();
  const errors=[];page.on('pageerror',error=>errors.push(String(error)));
  let restore=()=>{};
  try {
    await page.locator('[name=loginId]').waitFor({timeout:180000});
    const origin=new URL(page.url()).origin;
    const log=fs.readFileSync(path.join(profile,'service/mysql.log'),'utf8');
    const port=[...log.matchAll(/port: (\d+)\s+MySQL/g)].at(-1)[1];
    const credentials=JSON.parse(fs.readFileSync(path.join(profile,'service/credentials.json'),'utf8'));
    const sql=query=>execFileSync(path.join(installed,'resources/mysql/bin/mysql.exe'),['--no-defaults','--protocol=TCP','-h','127.0.0.1','-P',port,'-u','root','-N','-B','ictwork'],{input:query,encoding:'utf8',windowsHide:true,env:{...process.env,MYSQL_PWD:credentials.root}}).trim();
    const users=['bn1-cmdr','bn1-hq-s1-lead','bn1-co1-admin','bn1-co1-s2-m3'];
    const ids=users.map(user=>"'"+user+"'").join(',');
    const saved=sql('SELECT login_id,password_hash,password_change_required FROM account WHERE login_id IN ('+ids+');').split(/\r?\n/).map(row=>row.split('\t'));
    assert.equal(saved.length,users.length);
    restore=()=>sql(saved.map(([user,hash,required])=>"UPDATE account SET password_hash='"+hash+"',password_change_required="+required+" WHERE login_id='"+user+"';").join('\n'));
    const hash='$2b$12$yk3bmg6ARaWcA.OpWOIw2O3qMsEMtghYpzecJw58Td8xB2fG/RxXu';
    sql("UPDATE account SET password_hash='"+hash+"',password_change_required=0 WHERE login_id IN ("+ids+");");
    const results=[];
    const logout=async()=>{
      const token=(await page.context().cookies(origin)).find(cookie=>cookie.name==='XSRF-TOKEN');
      if(token) await page.request.post(origin+'/api/auth/logout',{headers:{'X-XSRF-TOKEN':decodeURIComponent(token.value)}});
      await page.goto(origin+'/signin');await page.locator('[name=loginId]').waitFor();
    };
    for(const user of users) {
      await logout();
      await page.locator('[name=loginId]').fill(user);await page.locator('[name=password]').fill('Local-Demo-Init!26');
      const [login]=await Promise.all([page.waitForResponse(response=>response.url().endsWith('/api/auth/login')&&response.request().method()==='POST'),page.locator('[name=password]').press('Enter')]);
      assert.equal(login.status(),200);
      await page.locator('.board-attention:not([data-state=loading])').waitFor();
      const response=await page.request.get(origin+'/api/ict-status');assert.equal(response.status(),200);
      const raw=await response.json();
      const operations=await (await page.request.get(origin+'/api/operations')).json();
      await page.waitForFunction(total=>document.querySelector('.board-metric-value')?.textContent===total+'개',operations.items.length);
      assert.equal((await page.locator('.board-metric-value').allTextContents())[1],operations.items.filter(item=>item.status==='NORMAL').length+'개');
      await page.goto(origin+'/operations');await page.locator('.ops-paging').waitFor();
      if(operations.items.length) await page.locator('.ops-table tbody tr').first().waitFor();
      assert.equal(await page.getByRole('button',{name:'체계 등록',exact:true}).count(),operations.createDepartments.length?1:0);
      assert.equal(await page.locator('.ops-table tbody').getByRole('button',{name:'체계 수정',exact:true}).count(),operations.items.slice(0,10).filter(item=>item.canManage).length);
      await page.goto(origin+'/board');await page.locator('.board-attention:not([data-state=loading])').waitFor();

      const visible=raw.boxes.filter(box=>!box.hidden);
      const alerts=visible.filter(box=>['RED','ORANGE','YELLOW'].includes(box.shownColor));
      assert.equal(await page.getByTestId('attention-count').innerText(),String(new Set(alerts.map(box=>box.id)).size));
      const shown=await page.locator('.board-core-indicator[href],.board-risk-row').evaluateAll(elements=>elements.map(element=>Number(new URL(element.href).searchParams.get('box'))));
      assert.ok(shown.every(id=>visible.some(box=>box.id===id)),'All displayed data must belong to this actor’s API scope');
      await page.getByRole('button',{name:'통신·경보 전체 현황',exact:true}).click();await page.getByRole('dialog').waitFor();
      const tableIds=await page.locator('.board-dialog-box').evaluateAll(elements=>elements.map(element=>Number(new URL(element.href).searchParams.get('box'))));
      assert.deepEqual([...tableIds].sort((a,b)=>a-b),visible.map(box=>box.id).sort((a,b)=>a-b));
      await page.keyboard.press('Escape');await page.getByRole('dialog').waitFor({state:'hidden'});
      await page.evaluate(()=>window.scrollTo(0,0));await page.waitForTimeout(150);
      await page.screenshot({path:path.join(evidence,'board-role-'+user+'.png'),fullPage:true,animations:'disabled'});
      const statistics=await require('./verify-layout-polish.cjs').checkScope(page,origin,evidence,user);
      const unifiedOperations=await require('./verify-operations-unified.cjs').checkActor(page,origin,evidence,user);
      await page.goto(origin+'/board');await page.locator('.board-attention:not([data-state=loading])').waitFor();
      results.push({user,operationIds:operations.items.map(item=>item.id),visibleBoxIds:visible.map(box=>box.id),attention:alerts.length,hiddenExcluded:true,statistics,unifiedOperations});
    }
    // The same mounted board clears department values when its certified scope disappears.
    const scopeEndpoint='**/api/work-status/scope';
    const oldScope=await (await page.request.get(origin+'/api/work-status/scope')).json();
    await page.route(scopeEndpoint,route=>route.fulfill({json:{...oldScope,initialIds:[]}}));
    await page.locator('.board-actions button:not([disabled])').waitFor();await page.locator('.board-actions button').click();
    await page.locator('.board-metric .board-resource-note[data-state=unavailable]').first().waitFor();
    assert.ok((await page.locator('.board-metric-value').allTextContents()).slice(2).every(value=>value.includes('—')));
    await page.unroute(scopeEndpoint);

    // Independent ICT availability while the work scope is deliberately slow.
    await page.route(scopeEndpoint,async route=>{await new Promise(resolve=>setTimeout(resolve,2000));await route.continue();});
    await page.goto(origin+'/board');
    await page.locator('.board-attention:not([data-state=loading])').waitFor();
    assert.equal(await page.locator('.board-work>.board-section-head>.board-resource-note[data-state=loading]').count(),1);
    await page.locator('.board-actions button:not([disabled])').waitFor();await page.unroute(scopeEndpoint);

    let calls=0;
    const ictEndpoint='**/api/ict-status';
    await page.route(ictEndpoint,async route=>{calls++;await new Promise(resolve=>setTimeout(resolve,1000));await route.continue();});
    await page.locator('.board-actions button').click();
    await page.evaluate(()=>{for(let index=0;index<6;index++)window.dispatchEvent(new Event('focus'));});
    await page.locator('.board-actions button:not([disabled])').waitFor();assert.equal(calls,1,'Focus events must not duplicate an active board refresh');
    await page.unroute(ictEndpoint);
    const shortStates=[];
    const shortCheck=async name=>{
      await page.setViewportSize({width:1366,height:660});await page.evaluate(()=>document.documentElement.dataset.fontSize='large');
      await page.locator('header').getByRole('button',{name:'AI 업무지원',exact:true}).click();await page.locator('#assist-panel').waitFor();
      const result=await require('./verify-board-fit.cjs').checkLayout(page,evidence,'short-'+name);
      shortStates.push({name,width:result.width,height:result.height,scrollY:result.scrollY});
      await page.locator('header').getByRole('button',{name:'AI 업무지원',exact:true}).click();await page.locator('#assist-panel').waitFor({state:'hidden'});
    };
    let failure='stale';
    const shortHandler=route=>route.fulfill(failure==='empty'?{json:{boxes:[],addDepartments:[]}}:{status:failure==='denied'?403:503,json:{message:'Synthetic short-window source failure'}});
    await page.route(ictEndpoint,shortHandler);
    for(const [mode,state] of [['stale','stale'],['denied','unavailable'],['empty','empty']]) {
      failure=mode;await page.locator('.board-actions button:not([disabled])').waitFor();await page.locator('.board-actions button').click();
      await page.locator(`.board-attention[data-state=${state}]`).waitFor();await shortCheck(mode);
    }
    await page.unroute(ictEndpoint,shortHandler);
    const failedEndpoints=/\/api\/(operations|ict-status|work-status(?:\/scope)?|work\/items|work\/receipts|directives|duty\/today)(?:\?.*)?$/;
    await page.route(failedEndpoints,route=>route.fulfill({status:503,json:{message:'Synthetic short-window total failure'}}));
    await page.goto(origin+'/board');await page.locator('.board-attention[data-state=error]').waitFor();await shortCheck('all-failed');
    await page.unroute(failedEndpoints);
    assert.deepEqual(errors,[]);
    fs.writeFileSync(path.join(evidence,'board-roles-result.json'),JSON.stringify({success:true,results,emptyScopeClearsOldValues:true,ictIndependentOfWork:true,refreshDeduplicated:true,shortStates,rendererErrors:errors},null,2));
    console.log('PASS four real role scopes, full ICT lists, scope change, independent ICT and refresh deduplication');
  } catch(error) {
    await page.screenshot({path:path.join(evidence,'board-roles-failure.png'),fullPage:true}).catch(()=>{});throw error;
  } finally {
    restore();
    const stopped=new Promise(resolve=>electron.process().once('exit',resolve));
    await page.close().catch(()=>{});
    await Promise.race([stopped,new Promise((_,reject)=>setTimeout(()=>reject(Error('App services did not stop')),25000).unref())]);
  }
})().catch(error=>{console.error(error);process.exitCode=1;});
