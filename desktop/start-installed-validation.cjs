// Installed-process lifecycle and local HTTP fixture only. UI checks use CUA.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const { setTimeout: delay } = require('node:timers/promises');
const { _electron } = require('playwright');
const { Database, writeJson } = require('./final-db-tools.cjs');
const root = path.resolve(__dirname, '..');
const [installed,evidence,profile] = process.argv.slice(2).map(value => path.resolve(value));
if (!installed || !evidence || !profile || !profile.startsWith(path.join(root,'output')+path.sep)) throw Error('Use explicit installed/evidence/owned output-profile paths');
fs.mkdirSync(evidence,{recursive:true});
const password = crypto.randomBytes(24).toString('base64url')+'!7a';
const bcrypt = value => execFileSync('python.exe',['-c','import sys,bcrypt;sys.stdout.write(bcrypt.hashpw(sys.stdin.buffer.read(),bcrypt.gensalt(rounds=10)).decode())'],{input:value,encoding:'utf8',windowsHide:true}).trim();
let app, db, originalAdmin, closing=false;
async function close() {
  if(closing) return; closing=true;
  try {
    if(originalAdmin && db) db.sql("UPDATE account SET password_hash='"+originalAdmin.password_hash+"',password_change_required="+originalAdmin.password_change_required+" WHERE login_id='sysadmin'");
  } finally { if(app) await app.close(); }
  process.stdin.pause();
}
(async()=>{
  const env={...process.env,PATH:path.join(process.env.SystemRoot,'System32'),ICTWORK_SCHEDULER_ENABLED:'false',ICTWORK_AI_ENABLED:'true',ICTWORK_AI_PROVIDER:'LOCAL',ICTWORK_QAMASTER_LOGINID:'installed-validation-master',ICTWORK_QAMASTER_PASSWORDHASH:bcrypt(password)};
  delete env.JAVA_HOME;delete env.JDK_HOME;delete env.ELECTRON_RUN_AS_NODE;
  app=await _electron.launch({executablePath:path.join(installed,'OMNIS.exe'),args:['--user-data-dir='+profile],env,timeout:60000});
  const service=path.join(profile,'service'), log=path.join(service,'spring.log');
  let origin;
  for(let attempt=0;attempt<960;attempt++) {
    const matches=fs.existsSync(log)?[...fs.readFileSync(log,'utf8').matchAll(/Tomcat started on port (\d+)/g)]:[];
    if(matches.length) {
      const candidate='http://127.0.0.1:'+matches.at(-1)[1];
      try { if((await fetch(candidate+'/signin',{signal:AbortSignal.timeout(700)})).ok){origin=candidate;break;} }catch{}
    }
    if(app.process().exitCode!==null) throw Error('Installed app exited before ready');
    await delay(250);
  }
  if(!origin) throw Error('Installed backend did not start');
  db=new Database({bundle:installed,service,evidence});
  db.port=Number(fs.readFileSync(path.join(service,'omnis-mysql.ini'),'utf8').match(/^port=(\d+)/m)[1]);
  const fixture={installed,evidence,profile,origin,frontend:origin,mysqlPort:db.port,schema:'ictwork',databaseUser:'ictwork',databasePassword:db.credentials.app,adminLogin:'sysadmin',adminPassword:password,regularPassword:'Local-Demo-Init!26',qaLogin:'installed-validation-master',qaPassword:password};
  writeJson(path.join(evidence,'fixture.json'),fixture);
  writeJson(path.join(evidence,'runtime.json'),{origin,profile,pid:app.process().pid,installed});
  console.log('INSTALLED VALIDATION READY '+origin);
  const readline=require('node:readline').createInterface({input:process.stdin});
  readline.on('line',async line=>{
    if(line.trim()==='fixture') {
      if(originalAdmin) return;
      originalAdmin=JSON.parse(db.sql("SELECT JSON_OBJECT('password_hash',password_hash,'password_change_required',password_change_required) FROM account WHERE login_id='sysadmin'"));
      db.sql("UPDATE account SET password_hash='"+bcrypt(password)+"',password_change_required=FALSE WHERE login_id='sysadmin'");
      console.log('DISPOSABLE ADMIN FIXTURE READY; shipped DB and original credentials unchanged');
    }
    if(line.trim()==='quit') {readline.close();await close();}
  });
  app.on('close',()=>{app=undefined;readline.close();process.stdin.pause();});
})().catch(async error=>{console.error(error);process.exitCode=1;await close();});
for(const signal of ['SIGINT','SIGTERM']) process.on(signal,()=>void close());
