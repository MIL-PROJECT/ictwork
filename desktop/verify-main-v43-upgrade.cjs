const {_electron}=require('playwright');
const path=require('node:path'),fs=require('node:fs'),assert=require('node:assert/strict');
const {execFileSync}=require('node:child_process');
const [bundle,evidence]=process.argv.slice(2).map(p=>path.resolve(p));const profile=path.join(evidence,'main-v43-fixture');
(async()=>{
 assert.ok(!fs.existsSync(profile));fs.mkdirSync(evidence,{recursive:true});
 for(const target43 of [true,false]) {
  const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;if(target43)env.SPRING_FLYWAY_TARGET='43';
  const app=await _electron.launch({executablePath:path.join(bundle,'OMNIS.exe'),args:['--user-data-dir='+profile],env,timeout:60000});const page=await app.firstWindow();
  try{
   if(target43){
    // Construct upstream main's exact V43 schema in an owned empty fixture.
    // Operations uses JDBC, so the login page can load before those tables exist.
    await page.locator('[name=loginId]').waitFor({timeout:180000});
    const log=fs.readFileSync(path.join(profile,'service/spring.log'),'utf8');assert.match(log,/now at version v43/);assert.ok(!log.includes('to version "44 - operation systems"'));
   }else{
    await page.locator('[name=loginId]').waitFor({timeout:180000});const origin=new URL(page.url()).origin;
    await page.locator('[name=loginId]').fill('bn1-co1-admin');await page.locator('[name=password]').fill('Local-Demo-Init!26');await page.locator('[name=password]').press('Enter');await page.locator('.board-control').waitFor();
    const me=await(await page.request.get(origin+'/api/me')).json();assert.equal(me.showGuides,true);
    const operations=await page.request.get(origin+'/api/operations');assert.equal(operations.status(),200);assert.equal((await operations.json()).items.length,0);
    const credentials=JSON.parse(fs.readFileSync(path.join(profile,'service/credentials.json'),'utf8'));const log=fs.readFileSync(path.join(profile,'service/mysql.log'),'utf8');const port=[...log.matchAll(/port: (\d+)\s+MySQL/g)].at(-1)[1];
    const history=execFileSync(path.join(bundle,'resources/mysql/bin/mysql.exe'),['--no-defaults','--protocol=TCP','-h','127.0.0.1','-P',port,'-u','root','-N','-B','ictwork','--execute=SELECT version,script FROM flyway_schema_history WHERE version IN (\'43\',\'44\') ORDER BY version'],{encoding:'utf8',windowsHide:true,env:{...process.env,MYSQL_PWD:credentials.root}}).trim();
    assert.match(history,/43\tV43__account_show_guides.sql/);assert.match(history,/44\tV44__operation_systems.sql/);assert.equal(fs.existsSync(path.join(profile,'service/legacy-v43-upgrade.json')),false);
    fs.writeFileSync(path.join(evidence,'result.json'),JSON.stringify({success:true,upstreamMainV43To44:true,legacyHistoryBridgeUsed:false,showGuidesPreserved:true,history,fixtureOnly:true},null,2));await page.screenshot({path:path.join(evidence,'upgraded.png')});
   }
  }finally{await app.close();}
 }
 console.log('PASS upstream main V43 guides to V44 operations without history rewrite');
})().catch(e=>{console.error(e);process.exitCode=1;});
