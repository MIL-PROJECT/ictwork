const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const{execFileSync}=require('node:child_process');const{_electron,request}=require('playwright');
const{Database,fileManifest,writeJson,hash}=require('./final-db-tools.cjs');const{assertPreserved}=require('./merge-final-db.cjs');
const output=path.resolve(process.argv[2]),bundle=path.resolve(process.argv[3]),evidence=path.join(output,'evidence'),profile=path.join(evidence,'fresh-installed-profile');
const manifest=JSON.parse(fs.readFileSync(path.join(output,'bootstrap/manifest.json'),'utf8')),ids=JSON.parse(fs.readFileSync(path.join(evidence,'id-map.json'),'utf8'));
const password=crypto.randomBytes(24).toString('base64url');
const passwordHash=execFileSync('python.exe',['-c','import sys,bcrypt;sys.stdout.write(bcrypt.hashpw(sys.stdin.buffer.read(),bcrypt.gensalt(rounds=10)).decode())'],{input:password,encoding:'utf8',windowsHide:true}).trim();
const env={...process.env,ICTWORK_SCHEDULER_ENABLED:'false',ICTWORK_AI_ENABLED:'false',ICTWORK_QAMASTER_LOGINID:'final-validation-master',ICTWORK_QAMASTER_PASSWORDHASH:passwordHash};delete env.ELECTRON_RUN_AS_NODE;
async function csrf(api){await api.get('/api/me');const cookie=(await api.storageState()).cookies.find(c=>c.name==='XSRF-TOKEN');assert.ok(cookie);return{'X-XSRF-TOKEN':decodeURIComponent(cookie.value)};}
async function post(api,url,data){return api.post(url,{headers:await csrf(api),data});}
async function okay(response,label){assert.equal(response.status(),200,label+': '+(await response.text()).slice(0,200));return response.json();}
async function login(api,loginId,secret){const response=await api.post('/api/auth/login',{headers:await csrf(api),form:{loginId,password:secret}});assert.equal(response.status(),200,'Login '+loginId);return okay(await api.get('/api/me'),'Current user');}
let calendarId,initialRows,initialMarker;
const report={success:false,runs:[],qa:{},readOnlyEndpoints:[]};
(async()=>{
 assert.ok(!fs.existsSync(profile),'Use a fresh validation profile');
 for(const run of [1,2]){
  const app=await _electron.launch({executablePath:path.join(bundle,'OMNIS.exe'),args:['--user-data-dir='+profile],env,timeout:60000});const page=await app.firstWindow();page.setDefaultTimeout(30000);
  let origin;
  try{
   await Promise.race([page.locator('[name=loginId]').waitFor({timeout:240000}),page.getByRole('heading',{name:'OMNIS 시작 실패',exact:true}).waitFor({timeout:240000}).then(async()=>{throw Error(await page.locator('body').innerText());})]);
   origin=new URL(page.url()).origin;
   const service=path.join(profile,'service'),db=new Database({bundle,service,evidence});db.port=Number(fs.readFileSync(path.join(service,'omnis-mysql.ini'),'utf8').match(/^port=(\d+)$/m)[1]);
   const marker=JSON.parse(fs.readFileSync(path.join(service,'final-dataset-installed.json'),'utf8'));assert.equal(marker.sqlSha256,manifest.sql.sha256);
   const qaConfig=fs.readFileSync(path.join(service,'config/application.yml'),'utf8');assert.equal(hash(qaConfig),manifest.qaConfig.sha256,'Bundled existing QA credentials are copied unchanged');
   const snapshot=db.snapshot();
   if(run===1){
    for(const[table,info]of Object.entries(manifest.tables)){assert.equal(snapshot[table].count,info.count,'Restored count '+table);assert.equal(snapshot[table].sha256,info.sha256,'Restored row hash '+table);}
    initialRows=snapshot;initialMarker=marker;
   }else{assertPreserved(initialRows,snapshot);assert.deepEqual(marker,initialMarker,'Restart must not import the dataset again');}
   await page.locator('[name=loginId]').fill('bn1-co1-admin');await page.locator('[name=password]').fill('Local-Demo-Init!26');await page.locator('[name=password]').press('Enter');await page.locator('.board-control').waitFor();
   await page.waitForFunction(()=>[...document.querySelectorAll('.board-metric-value')].length===4&&[...document.querySelectorAll('.board-metric-value')].every(e=>!e.textContent.includes('—')));
   const api=await request.newContext({baseURL:origin});
   try{
    await login(api,'bn1-co1-admin','Local-Demo-Init!26');
    const operations=await okay(await api.get('/api/operations'),'Operations');assert.equal(operations.items.length,38);
    if(run===1){
     const rows=db.rows("SELECT id FROM work_request WHERE requester_account_id=(SELECT id FROM account WHERE login_id='bn1-co1-admin') ORDER BY id").map(row=>row[0]);
     const imported=new Set(Object.values(ids.work_request)),oldRequest=rows.find(id=>!imported.has(id)),newRequest=rows.find(id=>imported.has(id));assert.ok(oldRequest&&newRequest);
     await okay(await api.get('/api/work/requests/'+oldRequest+'?from=mine'),'Original request');await okay(await api.get('/api/work/requests/'+newRequest+'?from=mine'),'Seed request');report.requests={original:oldRequest,imported:newRequest};
     const folder=await okay(await api.get('/api/doc-boxes/5'),'Merged document folder');assert.ok(JSON.stringify(folder).includes('최신 main 시연 자료'));
     for(const endpoint of ['/api/diagrams','/api/ict-status','/api/duty/today','/api/calendar/events']){await okay(await api.get(endpoint),endpoint);report.readOnlyEndpoints.push(endpoint);}
     const created=await okay(await post(api,'/api/calendar/events',{title:'최종 DB 복원·재시작 검증',startDate:'2026-11-08',endDate:'2026-11-08',color:'Primary',important:false,weekdaysOnly:false}),'Create calendar event');calendarId=created.id;
     const qa=await request.newContext({baseURL:origin});
     try{
      const master=await login(qa,'final-validation-master',password);assert.equal(master.qaMode,'MASTER');
      const accounts=await okay(await qa.get('/api/qa/accounts'),'QA account list');assert.equal(accounts.length,26);report.qa.accounts=accounts.length;report.qa.switched=[];
      for(const account of accounts){const response=await post(qa,'/api/qa/switch',{accountId:account.accountId});
       if(!account.active){assert.equal(response.status(),409);report.qa.inactiveBlocked=true;continue;}
       const switched=await okay(response,'Switch '+account.loginId);assert.equal(switched.loginId,account.loginId);report.qa.switched.push(account.loginId);
       if(account.loginId==='bn1-cmdr'){
        const diagramId=db.sql('SELECT id FROM diagram WHERE id IN ('+Object.values(ids.diagram).join(',')+') AND owner_department_id=1 LIMIT 1');assert.ok(diagramId);await okay(await qa.get('/api/diagrams/'+diagramId),'Imported diagram scene');
        const boxId=db.sql('SELECT id FROM ict_box WHERE id IN ('+Object.values(ids.ict_box).join(',')+') AND department_id IN (1,2,3,4,5,6,7,8,9,10,21,22) LIMIT 1');assert.ok(boxId);await okay(await qa.get('/api/ict-boxes/'+boxId),'Imported ICT box');
       }
       await okay(await post(qa,'/api/qa/return'),'Return to QA master');
      }
      assert.equal(report.qa.switched.length,25);
     }finally{await qa.dispose();}
    }else{const events=await okay(await api.get('/api/calendar/events'),'Persisted calendar');assert.ok(events.some(event=>event.id===calendarId),'User edit must survive a restart');}
   }finally{await api.dispose();}
   await page.screenshot({path:path.join(evidence,'final-db-dashboard-run-'+run+'.png'),animations:'disabled'});report.runs.push({run,bootstrapped:true,sourceRowsPreserved:true,operations:38});
  }catch(error){await page.screenshot({path:path.join(evidence,'final-db-failure.png')}).catch(()=>{});throw error;}finally{await app.close();}
  if(origin)assert.equal(await fetch(origin+'/signin',{signal:AbortSignal.timeout(1500)}).then(()=>true).catch(()=>false),false,'Backend must stop with Electron');
 }
 assert.deepEqual(fileManifest(path.join(process.env.APPDATA,'OMNIS Desktop Main/service')),JSON.parse(fs.readFileSync(path.join(evidence,'source-files-before.json'),'utf8')),'Original profile remains byte-for-byte unchanged');
 assert.equal(hash(fs.readFileSync(path.join(bundle,'resources/final-db/final-db.sql'))),manifest.sql.sha256);
 report.success=true;report.originalProfileUnchanged=true;report.exactRestoreVerified=true;report.restartAndUserEditVerified=true;report.qa.existingCredentialsPreserved=true;
 writeJson(path.join(evidence,'installed-validation.json'),report);console.log('PASS: exact database restore, 26 QA accounts, original/seed requests, documents/diagrams/ICT/duty/calendar, user edit + restart, clean shutdown, unchanged original profile');
})().catch(error=>{console.error(error.message);writeJson(path.join(evidence,'installed-validation-failure.json'),{success:false,message:error.message,...report});process.exitCode=1;});
