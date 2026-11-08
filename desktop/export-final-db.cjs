const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {Database,writeJson,fileManifest,hash,sceneHashes,startBackend}=require('./final-db-tools.cjs');
const {assertPreserved,metadata,readRows}=require('./merge-final-db.cjs');
const output=path.resolve(process.argv[2]),evidence=path.join(output,'evidence'),service=path.join(output,'final-profile/service'),bootstrap=path.join(output,'bootstrap');
const db=new Database({bundle:path.resolve('output/OMNIS-Main-Merged-Windows-x64'),service,evidence});
(async()=>{try{
 await db.start();
 const before=JSON.parse(fs.readFileSync(path.join(evidence,'source-rows.json'),'utf8'));
 const backend=await startBackend(db,{bundle:path.resolve('output/OMNIS-Main-Merged-Windows-x64'),war:path.resolve('src/backend/build/libs/ictwork-0.0.1.war'),database:'ictwork',cwd:service,logFile:path.join(evidence,'merged-main-startup.log')});
 await backend.stop();
 const current=db.snapshot();assertPreserved(before,current);assert.deepEqual(db.foreignKeyErrors(),[]);
 const mismatchedFiles=Number(db.sql('SELECT COUNT(*) FROM stored_file f JOIN stored_file_body b ON b.file_id=f.id WHERE f.sha256<>SHA2(b.data,256) OR f.size<>OCTET_LENGTH(b.data)'));
 assert.equal(mismatchedFiles,0,'All original/imported attachment bytes must match their metadata');
 const maps=JSON.parse(fs.readFileSync(path.join(evidence,'id-map.json'),'utf8'));
 const map=(table,id)=>maps[table]?.[String(id)]??String(id);
 const meta=metadata(db,'ictwork');
 const semantic={notificationLinkTemplates:db.rows("SELECT DISTINCT REGEXP_REPLACE(link,'[0-9]+','#') FROM final_seed.notification WHERE link IS NOT NULL"),fileAnswers:[],attributeFiles:[],boxTables:0,boxSchemas:0,sceneEquipment:0};
 const targetTypes=readRows(db,'request_target_type',meta.request_target_type,'ictwork');
 const answers=readRows(db,'request_answer_file',meta.request_answer_file,'final_seed');
 for(const answer of answers){
  const target=targetTypes.find(row=>row.id===map('request_target_type',answer.request_target_type_id));assert.ok(target);
  const value=JSON.parse(target.answers)[answer.question_key];
  semantic.fileAnswers.push({question:answer.question_key,value,expectedFileId:map('stored_file',answer.file_id)});
  assert.ok(JSON.stringify(value).includes(map('stored_file',answer.file_id)),'File answer JSON must refer to the imported attachment: '+JSON.stringify({value,expected:map('stored_file',answer.file_id)}));
 }
 for(const row of readRows(db,'attribute_value_file',meta.attribute_value_file,'ictwork')){
  const table=row.target==='EQUIPMENT'?'equipment':'personnel';
  const values=db.sql(`SELECT COALESCE(attr_values,'{}') FROM ${table} WHERE id=${Number(row.owner_id)}`);
  semantic.attributeFiles.push({fileId:row.file_id,ownerTable:table,ownerId:row.owner_id,linkedInValues:values.includes(row.file_id)});
 }
 for(const row of readRows(db,'ict_box',meta.ict_box,'ictwork').concat(readRows(db,'ict_box_revision',meta.ict_box_revision,'ictwork'))){
  assert.equal(db.sql('SELECT COUNT(*) FROM ict_box_schema WHERE id='+Number(row.schema_id)),'1');semantic.boxSchemas++;
  for(const value of Object.values(JSON.parse(row.tables_json))){assert.equal(db.sql('SELECT COUNT(*) FROM ict_box_table WHERE id='+Number(value)),'1');semantic.boxTables++;}
 }
 const scenes=readRows(db,'diagram_version_scene',meta.diagram_version_scene,'ictwork'),canonicalHashes=sceneHashes(scenes.map(row=>row.scene));
 for(const [index,row]of scenes.entries()){
  assert.equal(db.sql('SELECT scene_hash FROM diagram_version WHERE id='+Number(row.version_id)),canonicalHashes[index],'Canonical backend scene hash: '+row.version_id);
  const scene=JSON.parse(row.scene);
  for(const element of scene.elements||[]){const id=element.customData?.equipmentId;if(id!=null){assert.equal(db.sql('SELECT COUNT(*) FROM equipment WHERE id='+Number(id)),'1');semantic.sceneEquipment++;}}
 }
 writeJson(path.join(evidence,'semantic-validation.json'),{success:true,attachmentHashesValid:true,...semantic});
 fs.mkdirSync(bootstrap,{recursive:true});
 const finalSql=path.join(output,'final-db.sql');
 const sql=await db.dump('ictwork',finalSql);fs.copyFileSync(finalSql,path.join(bootstrap,'final-db.sql'));
 const sourceConfig=path.join(service,'config/application.yml');let qaConfig;
 if(fs.existsSync(sourceConfig)){
  const content=fs.readFileSync(sourceConfig,'utf8');
  const login=content.match(/^\s*login-id:\s*["']?([^\s"']+)["']?\s*$/m)?.[1];
  const passwordHash=content.match(/^\s*password-hash:\s*["'](\$2[aby]?\$10\$[./A-Za-z0-9]{53})["']\s*$/m)?.[1];
  assert.ok(login&&passwordHash,'Existing QA config must be copied exactly, without guessing credentials');
  const qa='ictwork:\n  qa-master:\n    login-id: '+JSON.stringify(login)+'\n    password-hash: '+JSON.stringify(passwordHash)+'\n';
  fs.writeFileSync(path.join(bootstrap,'qa-config.yml'),qa);
  qaConfig={loginId:login,sha256:hash(qa),bcryptCost:10,existingCredentialsPreserved:true};
 }
 const inputs=JSON.parse(fs.readFileSync(path.join(output,'build-inputs.json'),'utf8'));
 const manifest={format:1,createdAt:new Date().toISOString(),sourceCommit:inputs.commit,defaultProfile:'OMNIS Desktop Final',warSha256:hash(fs.readFileSync(inputs.war)),sql:{bytes:sql.bytes,sha256:sql.sha256},qaConfig,tables:Object.fromEntries(Object.entries(current).map(([table,{count,sha256}])=>[table,{count,sha256}])),sourceRowsPreserved:true,seedPolicy:'existing core identities preserved; main demo business graph imported under remapped IDs'};
 writeJson(path.join(bootstrap,'manifest.json'),manifest);writeJson(path.join(output,'final-db-manifest.json'),manifest);
 console.log(JSON.stringify({success:true,sql,qaLogin:qaConfig?.loginId,tables:Object.keys(current).length,rows:Object.values(current).reduce((n,table)=>n+table.count,0),semantic},null,2));
}finally{await db.stop();
 assert.deepEqual(fileManifest(path.join(process.env.APPDATA,'OMNIS Desktop Main/service')),JSON.parse(fs.readFileSync(path.join(evidence,'source-files-before.json'),'utf8')),'Original source files remain unchanged');
}})().catch(e=>{console.error(e.message);process.exitCode=1;});
