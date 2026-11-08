// Actual installed services via HTTP/SQL only; no browser or desktop UI control.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {request}=require('playwright');
const {Database,hash,writeJson}=require('./final-db-tools.cjs');
const fixture=JSON.parse(fs.readFileSync(path.resolve(process.argv[2]),'utf8'));
const resume=process.argv.includes('--resume');
const db=new Database({bundle:fixture.installed,service:path.join(fixture.profile,'service'),evidence:fixture.evidence});db.port=fixture.mysqlPort;
const manifest=JSON.parse(fs.readFileSync(path.join(fixture.installed,'resources/final-db/manifest.json'),'utf8'));
(async()=>{
 const report={success:false,resume,checks:[]};
 const api=await request.newContext({baseURL:fixture.origin,timeout:30000});
 async function okay(response,label){assert.equal(response.status(),200,label+': '+(await response.text()).slice(0,220));return response.json();}
 async function csrf(){await api.get('/api/me');const token=(await api.storageState()).cookies.find(c=>c.name==='XSRF-TOKEN');assert.ok(token);return{'X-XSRF-TOKEN':decodeURIComponent(token.value)};}
 try{
  const marker=JSON.parse(fs.readFileSync(path.join(fixture.profile,'service/final-dataset-installed.json'),'utf8'));
  assert.equal(marker.sqlSha256,manifest.sql.sha256);
  assert.equal(hash(fs.readFileSync(path.join(fixture.profile,'service/config/application.yml'))),manifest.qaConfig.sha256);
  report.checks.push('bundled SQL marker and preserved original QA configuration');
  if(!resume){
   const snapshot=db.snapshot();
   const derived=new Set(['ai_source','ai_chunk']);
   for(const [table,info] of Object.entries(manifest.tables)){if(derived.has(table))continue;assert.equal(snapshot[table]?.count,info.count,table+' row count');assert.equal(snapshot[table]?.sha256,info.sha256,table+' original row hashes');}
   report.tablesVerified=Object.keys(manifest.tables).filter(table=>!derived.has(table)).length;
   report.derivedIndexes=Object.fromEntries([...derived].map(table=>[table,snapshot[table].count]));
   report.checks.push('fresh installation reproduces all non-derived table counts and row fingerprints; source/chunk search indexes rebuild normally');
  }
  await okay(await api.post('/api/auth/login',{headers:await csrf(),form:{loginId:'bn1-co1-admin',password:fixture.regularPassword}}),'Bundled existing account login');
  const me=await okay(await api.get('/api/me'),'Current account');assert.equal(me.loginId,'bn1-co1-admin');
  report.checks.push('existing non-admin credential login');
  for(const endpoint of ['/api/operations','/api/work/items','/api/work/receipts','/api/directives','/api/diagrams','/api/ict-status','/api/duty/today','/api/calendar/events','/api/doc-boxes/5']){
   await okay(await api.get(endpoint),endpoint);report.checks.push('GET '+endpoint);
  }
  const operations=await okay(await api.get('/api/operations'),'Operation count');assert.equal(operations.items.length,38);
  const persistence=path.join(fixture.evidence,'persistence.json');
  if(!resume){
   const title='OMNIS 설치 보존 검증 '+crypto.randomBytes(4).toString('hex');
   const event=await okay(await api.post('/api/calendar/events',{headers:await csrf(),data:{title,startDate:'2026-11-09',endDate:'2026-11-09',color:'Primary',important:false,weekdaysOnly:false}}),'Save calendar marker');
   writeJson(persistence,{calendarId:event.id,title,datasetMarker:marker});
  }
  const expected=JSON.parse(fs.readFileSync(persistence,'utf8'));
  const events=await okay(await api.get('/api/calendar/events'),'Calendar persistence');assert.ok(events.some(event=>event.id===expected.calendarId&&event.title===expected.title));
  assert.deepEqual(marker,expected.datasetMarker,'Existing profile must never re-import the dataset');
  report.checks.push('saved calendar and dataset marker persist');
  const html=await api.get('/signin');assert.equal(html.status(),200);
  const script=(await html.text()).match(/src="(\/assets\/[^" ]+\.js)"/);assert.ok(script,'Bundled production JS entry');
  assert.equal((await api.get(script[1])).status(),200);report.checks.push('bundled production frontend served from installed WAR');
  report.success=true;writeJson(path.join(fixture.evidence,resume?'baseline-resume.json':'baseline.json'),report);console.log(JSON.stringify(report));
 }finally{await api.dispose();}
})().catch(error=>{console.error(error);process.exitCode=1;});
