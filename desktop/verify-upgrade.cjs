const { _electron } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const [bundle,sourceEvidence,evidence]=process.argv.slice(2).map(p=>path.resolve(p));
const source=JSON.parse(fs.readFileSync(path.join(sourceEvidence,'installed-smoke-result.json'),'utf8'));
const profile=path.join(evidence,'V42 복사본 V44');
function manifest(root) {
  return fs.readdirSync(root,{withFileTypes:true}).flatMap(entry=>{
    const p=path.join(root,entry.name);
    return entry.isDirectory()?manifest(p):[[p,crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex')]];
  });
}
function copyProfile(source,destination) {
  fs.mkdirSync(destination,{recursive:true});
  for(const entry of fs.readdirSync(source,{withFileTypes:true})) {
    assert.ok(!entry.isSymbolicLink(),'Synthetic profile must not contain external links');
    const from=path.join(source,entry.name),to=path.join(destination,entry.name);
    if(entry.isDirectory()) copyProfile(from,to);
    else fs.copyFileSync(from,to);
  }
}
(async()=>{
  assert.ok(source.success);
  assert.ok(!fs.existsSync(profile),'Use a new copied test profile');
  assert.match(fs.readFileSync(path.join(source.profile,'service/spring.log'),'utf8'),/version[^\n]*42|42[^\n]*migration/i);
  const before=manifest(source.profile);
  console.log('Original V42 profile hashed:',before.length,'files');
  fs.mkdirSync(evidence,{recursive:true});
  copyProfile(source.profile,profile);
  console.log('Copied isolated V42 profile');
  const env={...process.env,PATH:path.join(process.env.SystemRoot,'System32'),ICTWORK_AI_ENABLED:'false'};
  delete env.JAVA_HOME; delete env.JDK_HOME; delete env.ELECTRON_RUN_AS_NODE;
  const app=await _electron.launch({executablePath:path.join(bundle,'OMNIS.exe'),args:['--user-data-dir='+profile],env,timeout:60000});
  const page=await app.firstWindow();
  page.setDefaultTimeout(20000);
  let origin;
  let report;
  try {
    assert.equal(path.resolve(await app.evaluate(({app})=>app.getPath('userData'))),profile);
    await page.locator('[name=loginId]').waitFor({timeout:180000});
    origin=new URL(page.url()).origin;
    await page.locator('[name=loginId]').fill('bn1-co1-admin');
    await page.locator('[name=password]').fill('Local-Demo-Init!26');
    const [login]=await Promise.all([page.waitForResponse(r=>r.url().endsWith('/api/auth/login')&&r.request().method()==='POST'),page.locator('[name=password]').press('Enter')]);
    assert.equal(login.status(),200);
    await page.locator('aside').first().getByRole('link',{name:'업무 처리',exact:true}).waitFor();
    const workflow=JSON.parse(fs.readFileSync(path.join(sourceEvidence,'workflow-result.json'),'utf8'));
    for(const [id,status] of [[workflow.completedItemId,'DONE'],[workflow.rejectedItemId,'REJECTED']]) {
      const response=await page.request.get(origin+'/api/work/items/'+id+'?from=department');
      assert.equal(response.status(),200);
      assert.equal((await response.json()).status,status);
    }
    const registrations=JSON.parse(fs.readFileSync(path.join(sourceEvidence,'registration-result.json'),'utf8'));
    for(const id of [registrations.uiRequestId,registrations.pendingRequestId]) {
      const response=await page.request.get(origin+'/api/work/requests/'+id+'?from=mine');
      assert.equal(response.status(),200);
      const data=await response.json();
      assert.equal(data.kind,'REGISTER'); assert.equal(data.phase,'SENT'); assert.equal(data.items.length,1);
    }
    const events=await (await page.request.get(origin+'/api/calendar/events')).json();
    assert.ok(events.some(e=>e.id===source.runs[0].eventId));
    const credentials=JSON.parse(fs.readFileSync(path.join(profile,'service/credentials.json'),'utf8'));
    const log=fs.readFileSync(path.join(profile,'service/mysql.log'),'utf8');
    const port=[...log.matchAll(/port: (\d+)\s+MySQL/g)].at(-1)[1];
    const sql=query=>execFileSync(path.join(bundle,'resources/mysql/bin/mysql.exe'),[
      '--no-defaults','--protocol=TCP','-h','127.0.0.1','-P',port,'-u','root','-N','-B','ictwork',
    ],{input:query,encoding:'utf8',windowsHide:true,env:{...process.env,MYSQL_PWD:credentials.root}}).trim();
    assert.equal(sql('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1'),'44');
    assert.equal(sql('SELECT COUNT(*) FROM operation_system'),'0');
    assert.equal(sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='ictwork' AND table_name='ai_message' AND column_name='assist'"),'1');
    await page.goto(origin+'/tasks?tab=department');
    await page.locator('main').getByRole('link',{name:'우리 부서 업무 등록',exact:true}).waitFor();
    await page.screenshot({animations:'disabled',path:path.join(evidence,'upgraded-department-list.png')});
    report = {
      success:true,sourceProfile:source.profile,copiedProfile:profile,fromSchema:42,toSchema:44,
      preservedWorkflow:workflow,preservedRegistration:registrations,preservedCalendarEvent:source.runs[0].eventId,
      sourceFilesChecked:before.length,
    };
  } catch(error) {
    await page.screenshot({path:path.join(evidence,'upgrade-failure.png')}).catch(()=>{});
    throw error;
  } finally {
    await app.close();
  }
  assert.deepEqual(manifest(source.profile),before,'The original V42 profile must remain byte-for-byte unchanged');
  assert.equal(await fetch(origin+'/signin',{signal:AbortSignal.timeout(1500)}).then(()=>true).catch(()=>false),false);
  fs.writeFileSync(path.join(evidence,'upgrade-result.json'),JSON.stringify(report,null,2));
  console.log('PASS copied V42-to-V44 upgrade, persisted work/approvals/calendar, unchanged original profile and normal shutdown');
})().catch(error=>{console.error(error);process.exitCode=1;});
