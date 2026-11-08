const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const DATASET = 'legacy-38-v1';
const PREFIX = 'DEMO-OMNIS-';
const categories = ['기반통신','작전통신','사이버보호','전송장비','무선/위성','CCTV','지원체계'];
const catalog = Array.from({length:38},(_,i)=>{
  const status = i===1 || (i>=34 && i<=36) ? 'WATCH' : i===2 || i===37 ? 'INSPECTION' : i===3 ? 'FAULT' : i===4 ? 'OFFLINE' : 'NORMAL';
  return {code:PREFIX+String(i+1).padStart(3,'0'),name:categories[i%7]+' '+String(Math.floor(i/7)+1).padStart(2,'0'),category:categories[i%7],status,
    uptimePct:status==='OFFLINE'?0:status==='FAULT'?73.2:status==='WATCH'?94.2:status==='INSPECTION'?96.4:Math.round((96+i/10)*100)/100,
    remarks:'합성 시연 자료 · '+DATASET+' · 담당자 기록 운용률(자동 계측 아님)'};
});

// Reuse the existing authenticated API; never alter schema, permissions or SQL.
async function seedOperations({request,manifestPath,dryRun=false}) {
  const call=async(method,url,data,expected=200)=>{
    const response=await request(method,url,data);
    assert.equal(response.status,expected,`${method} ${url}: ${response.status} ${response.data?.message??''}`);
    return response.data;
  };
  const me=await call('GET','/api/me');
  const before=await call('GET','/api/operations');
  const department=before.createDepartments.find(d=>d.id===me.departmentId);
  assert.ok(department,'The current actor must manage their own active department; no automatic reassignment or permission bypass');
  const plan={dataset:DATASET,loginId:me.loginId,department,beforeItems:before.items,created:[],skipped:[],dryRun};
  const save=()=>{if(manifestPath){fs.mkdirSync(path.dirname(manifestPath),{recursive:true});fs.writeFileSync(manifestPath,JSON.stringify(plan,null,2));}};
  save();
  if(dryRun) return {...plan,toCreate:catalog.filter(row=>!before.items.some(item=>item.code===row.code))};
  try {
    for(const [index,row] of catalog.entries()) {
      const existing=before.items.find(item=>item.code===row.code);
      if(existing) {
        assert.equal(existing.departmentId,department.id,'A namespace collision in a different department requires review');
        assert.ok(existing.remarks.includes(DATASET),'Never adopt or overwrite an unrelated record with the same code');
        plan.skipped.push({id:existing.id,code:existing.code});save();continue;
      }
      let detail;
      try { detail=await call('POST','/api/operations',{...row,departmentId:department.id},201); }
      catch(error) {
        // A timeout can follow a committed write. Inspect the visible result;
        // unknown/deleted code conflicts are reported, never forcibly replaced.
        const latest=await call('GET','/api/operations');
        const recovered=latest.items.find(item=>item.code===row.code && item.departmentId===department.id && item.remarks===row.remarks && item.name===row.name);
        if(!recovered) throw error;
        plan.skipped.push({id:recovered.id,code:recovered.code,recoveredAfterUncertainWrite:true});save();continue;
      }
      plan.created.push({id:detail.item.id,code:row.code});save();
      if(index<3) {
        for(const update of [{status:'WATCH',uptimePct:91.5},{status:row.status,uptimePct:row.uptimePct}]) {
          detail=await call('PUT',`/api/operations/${detail.item.id}/status`,{...update,remarks:row.remarks,version:detail.item.version});
        }
      }
    }
    const after=await call('GET','/api/operations');
    const seeded=after.items.filter(item=>catalog.some(row=>row.code===item.code));
    assert.equal(seeded.length,38);
    for(const item of before.items) assert.deepEqual(after.items.find(row=>row.id===item.id),item,'Existing records must remain unchanged');
    plan.totalAfter=after.items.length;
    plan.seeded=seeded;
    plan.counts=Object.fromEntries(['NORMAL','WATCH','INSPECTION','FAULT','OFFLINE'].map(status=>[status,seeded.filter(item=>item.status===status).length]));
    plan.success=true;save();return plan;
  } catch(error) {plan.success=false;plan.error=String(error);save();throw error;}
}

module.exports={seedOperations,catalog,DATASET,PREFIX};

if(require.main===module) (async()=>{
  const args=process.argv.slice(2);const value=key=>args[args.indexOf(key)+1];
  assert.ok(args.includes('--origin')&&args.includes('--manifest'));
  const origin=new URL(value('--origin'));
  assert.ok(origin.protocol==='http:' && ['127.0.0.1','localhost','[::1]'].includes(origin.hostname) && origin.pathname==='/','Only a loopback service is allowed');
  const jar=new Map();
  const request=async(method,url,data)=>{
    const headers={Cookie:[...jar].map(([k,v])=>`${k}=${v}`).join('; ')};
    if(method!=='GET'&&jar.has('XSRF-TOKEN')) headers['X-XSRF-TOKEN']=decodeURIComponent(jar.get('XSRF-TOKEN'));
    if(data && !(data instanceof URLSearchParams))headers['Content-Type']='application/json';
    const response=await fetch(new URL(url,origin),{method,headers,body:data instanceof URLSearchParams?data:data?JSON.stringify(data):undefined,redirect:'manual',signal:AbortSignal.timeout(15000)});
    for(const cookie of response.headers.getSetCookie()) {const [pair]=cookie.split(';');const index=pair.indexOf('=');jar.set(pair.slice(0,index),pair.slice(index+1));}
    return {status:response.status,data:await response.json().catch(()=>null)};
  };
  await request('GET','/api/me');
  const login=await request('POST','/api/auth/login',new URLSearchParams({loginId:process.env.OMNIS_DEMO_LOGIN||'bn1-co1-admin',password:process.env.OMNIS_DEMO_PASSWORD||''}));
  assert.equal(login.status,200,'Demo API login failed (password is read from the environment, never logged)');
  const result=await seedOperations({request,manifestPath:path.resolve(value('--manifest')),dryRun:args.includes('--dry-run')});
  console.log(JSON.stringify({dataset:DATASET,loginId:result.loginId,department:result.department,created:result.created.length,skipped:result.skipped.length,counts:result.counts,dryRun:result.dryRun,success:result.success},null,2));
})().catch(error=>{console.error(String(error));process.exitCode=1;});
