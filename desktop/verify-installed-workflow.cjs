// Own-department synthetic work lifecycle in a disposable installed profile.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {request}=require('playwright');
const fixture=JSON.parse(fs.readFileSync(path.resolve(process.argv[2]),'utf8'));
(async()=>{
 const api=await request.newContext({baseURL:fixture.origin,timeout:30000});
 async function headers(){await api.get('/api/me');const c=(await api.storageState()).cookies.find(c=>c.name==='XSRF-TOKEN');assert.ok(c);return{'X-XSRF-TOKEN':decodeURIComponent(c.value)};}
 async function post(route,data,status=204){const response=await api.post(route,{headers:await headers(),data});assert.equal(response.status(),status,route+': '+await response.text());return response;}
 try{
  const login=await api.post('/api/auth/login',{headers:await headers(),form:{loginId:'bn1-co1-admin',password:fixture.regularPassword}});assert.equal(login.status(),200);
  const me=await(await api.get('/api/me')).json();
  const types=await(await api.get('/api/work/types/all')).json();
  const type=types.find(t=>t.departmentId===me.departmentId&&t.active&&t.isOther);assert.ok(type);
  const body={departmentIntake:true,place:'Installer validation',content:'InstallerWorkflow-'+Date.now(),targets:[{departmentId:me.departmentId,types:[{workTypeId:type.typeId}]}],viewers:{departmentIds:[],personnelIds:[]}};
  await post('/api/work/requests',{...body,place:''},400);
  const created=await(await post('/api/work/requests',body,201)).json();
  const detail=await(await api.get('/api/work/requests/'+created.id+'?from=mine')).json();const itemId=detail.items[0].id;
  await post('/api/work/items/'+itemId+'/receive',{});
  await post('/api/work/items/'+itemId+'/plan',{plan:{autoComplete:false,slots:[{startDate:'2026-11-09',personnelIds:[],equipmentIds:[]}]}});
  await post('/api/work/items/'+itemId+'/complete-approval',{steps:[{kind:'APPROVE',accountId:me.accountId}]});
  const completed=await(await api.get('/api/work/items/'+itemId+'?from=department')).json();assert.equal(completed.status,'DONE');
  const rejected=await(await post('/api/work/requests',{...body,content:body.content+'-reject'},201)).json();
  const rejectDetail=await(await api.get('/api/work/requests/'+rejected.id+'?from=mine')).json();const rejectItem=rejectDetail.items[0].id;
  await post('/api/work/items/'+rejectItem+'/reject',{reason:''},400);
  await post('/api/work/items/'+rejectItem+'/reject',{reason:'Disposable installer regression check'});
  const result={success:true,requestId:created.id,itemId,completedStatus:completed.status,rejectedItemId:rejectItem,checks:['required input validation','request submission','department receipt','planning','completion approval','reason validation and rejection']};
  fs.writeFileSync(path.join(fixture.evidence,'workflow.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
 }finally{await api.dispose();}
})().catch(error=>{console.error(error);process.exitCode=1;});
