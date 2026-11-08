const {_electron}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {execFileSync,spawn}=require('node:child_process');
const [oldBundle,bundle,sourceEvidence,evidence]=process.argv.slice(2).map(p=>path.resolve(p));
const source=JSON.parse(fs.readFileSync(path.join(sourceEvidence,'installed-smoke-result.json'),'utf8'));
const profile=path.join(evidence,'copied-legacy-v43');
const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
const manifest=root=>fs.readdirSync(root,{withFileTypes:true}).flatMap(entry=>{const p=path.join(root,entry.name);assert.ok(!entry.isSymbolicLink());return entry.isDirectory()?manifest(p):[[p,hash(fs.readFileSync(p))]];});
async function run(installed,phase,fn){
 const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;
 const app=await _electron.launch({executablePath:path.join(installed,'OMNIS.exe'),args:['--user-data-dir='+profile],env,timeout:60000});const page=await app.firstWindow();page.setDefaultTimeout(20000);
 try{
  await Promise.race([page.locator('[name=loginId]').waitFor({timeout:180000}),page.getByRole('heading',{name:'OMNIS 시작 실패',exact:true}).waitFor({timeout:180000}).then(async()=>{throw Error(await page.locator('body').innerText());})]);
  const origin=new URL(page.url()).origin;
  await page.locator('[name=loginId]').fill('bn1-co1-admin');await page.locator('[name=password]').fill('Local-Demo-Init!26');await page.locator('[name=password]').press('Enter');await page.locator('.board-attention:not([data-state=loading])').waitFor();
  const credentials=JSON.parse(fs.readFileSync(path.join(profile,'service/credentials.json'),'utf8'));
  const log=fs.readFileSync(path.join(profile,'service/mysql.log'),'utf8'),port=[...log.matchAll(/port: (\d+)\s+MySQL/g)].at(-1)[1];
  const sql=query=>execFileSync(path.join(installed,'resources/mysql/bin/mysql.exe'),['--no-defaults','--protocol=TCP','-h','127.0.0.1','-P',port,'-u','root','-N','-B','ictwork'],{input:query,encoding:'utf8',windowsHide:true,env:{...process.env,MYSQL_PWD:credentials.root}}).trim();
  const snapshot={operations:hash(sql('SELECT * FROM operation_system ORDER BY id')),history:hash(sql('SELECT * FROM operation_history ORDER BY id')),operationsCount:sql('SELECT COUNT(*) FROM operation_system'),flyway:sql("SELECT version,script,checksum FROM flyway_schema_history WHERE version IN ('43','44') ORDER BY version"),me:await(await page.request.get(origin+'/api/me')).json()};
  await fn({page,origin,sql,credentials,port,snapshot});
  await page.screenshot({path:path.join(evidence,phase+'.png'),animations:'disabled'});return snapshot;
 }catch(error){await page.screenshot({path:path.join(evidence,phase+'-failure.png')}).catch(()=>{});throw error;}finally{await app.close();}
}
(async()=>{
 assert.ok(source.success);assert.ok(!fs.existsSync(profile));fs.mkdirSync(evidence,{recursive:true});const beforeFiles=manifest(source.profile);fs.cpSync(source.profile,profile,{recursive:true,errorOnExist:true,force:false});
 const before=await run(oldBundle,'before',async()=>{});assert.match(before.flyway,/43\tV43__operation_systems.sql\t2029262443/);
 const after=await run(bundle,'upgraded',async({page,origin,sql,credentials,port,snapshot})=>{
  assert.equal(snapshot.operations,before.operations);assert.equal(snapshot.history,before.history);assert.equal(snapshot.operationsCount,before.operationsCount);
  assert.match(snapshot.flyway,/43\tV43__account_show_guides.sql/);assert.match(snapshot.flyway,/44\tV44__operation_systems.sql\t1824834412/);assert.equal(snapshot.me.showGuides,true);
  const marker=JSON.parse(fs.readFileSync(path.join(profile,'service/legacy-v43-upgrade.json'),'utf8'));assert.equal(marker.phase,'history-mapped');assert.equal(hash(fs.readFileSync(marker.backup.path)),marker.backup.sha256);
  const workflow=JSON.parse(fs.readFileSync(path.join(sourceEvidence,'workflow-result.json'),'utf8'));
  for(const [id,status]of[[workflow.completedItemId,'DONE'],[workflow.rejectedItemId,'REJECTED']]){const item=await page.request.get(origin+'/api/work/items/'+id+'?from=department');assert.equal(item.status(),200);assert.equal((await item.json()).status,status);}
  const token=(await page.context().cookies(origin)).find(c=>c.name==='XSRF-TOKEN');
  const changed=await page.request.put(origin+'/api/me/guides',{headers:{'X-XSRF-TOKEN':decodeURIComponent(token.value)},data:{showGuides:false}});assert.ok(changed.ok());await page.reload();await page.locator('html[data-guides=off]').waitFor({state:'attached'});
  assert.equal(await page.locator('.board-metric-meter').getAttribute('title'),null);
  const reset=await page.request.put(origin+'/api/me/guides',{headers:{'X-XSRF-TOKEN':decodeURIComponent(token.value)},data:{showGuides:true}});assert.ok(reset.ok());
  // The backend suite uses a new database in this owned, copied MySQL instance.
  sql('CREATE DATABASE ictwork_test CHARACTER SET utf8mb4;');
  const log=fs.openSync(path.join(evidence,'backend-tests.log'),'w');
  const testCommand='src\\backend\\gradlew.bat -p src/backend test --offline --no-daemon'+(process.env.OMNIS_AI_TESTS_ONLY==='1'?' --tests kr.ictwork.ai.AiQaTest':'');
  const gradle=spawn('cmd.exe',['/d','/s','/c',testCommand],{cwd:process.cwd(),windowsHide:true,stdio:['ignore',log,log],env:{...process.env,JAVA_HOME:path.resolve('output/runtime/jdk-17.0.21.0.2+1'),GRADLE_USER_HOME:path.resolve('output/runtime/gradle'),SPRING_DATASOURCE_URL:`jdbc:mysql://127.0.0.1:${port}/ictwork_test?connectionTimeZone=%2B09:00&forceConnectionTimeZoneToSession=true&preserveInstants=false&characterEncoding=UTF-8`,SPRING_DATASOURCE_USERNAME:'root',SPRING_DATASOURCE_PASSWORD:credentials.root}});
  fs.closeSync(log);const code=await new Promise((resolve,reject)=>{gradle.once('error',reject);gradle.once('close',resolve);});assert.equal(code,0,'Backend tests must pass; inspect backend-tests.log');
 });
 const restarted=await run(bundle,'restarted',async({snapshot})=>{assert.equal(snapshot.operations,before.operations);assert.equal(snapshot.history,before.history);assert.equal(snapshot.me.showGuides,true);});
 assert.deepEqual(manifest(source.profile),beforeFiles,'Original V43 profile must remain unchanged');
 fs.writeFileSync(path.join(evidence,'upgrade-result.json'),JSON.stringify({success:true,before,after,restarted,originalProfileUnchanged:true,backupVerified:true,backendTestsPassed:true,backendScope:process.env.OMNIS_AI_TESTS_ONLY==='1'?'AiQaTest':'all'},null,2));console.log('PASS copied V43 upgrade, verified backup, unchanged operations/history, main guide setting, selected backend tests and restart');
})().catch(error=>{console.error(error);process.exitCode=1;});
