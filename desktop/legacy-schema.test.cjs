const {test}=require('node:test'),assert=require('node:assert/strict');
const {prepareLegacySchema,LEGACY_CHECKSUM,MAIN_CHECKSUM}=require('./legacy-schema.cjs');
function harness(rows=[],{exists=true,tables='2',backupFails=false}={}) {
 const calls=[];
 return {calls,args:{query:async sql=>{calls.push(sql);if(sql.includes("table_name='flyway_schema_history'"))return [[exists?'1':'0']];if(sql.includes('SELECT version'))return rows;if(sql.includes('operation_history'))return [[tables]];if(sql.startsWith('START TRANSACTION'))return [['1']];throw Error(sql);},backup:async()=>{calls.push('backup');if(backupFails)throw Error('backup failed');return {path:'owned-backup.sql',sha256:'verified'};},record:async value=>calls.push(value.phase)}};
}
test('fresh and main V43 databases are never rewritten',async()=>{
 for(const h of [harness([],{exists:false}),harness([['43','V43__account_show_guides.sql','12','1']])]) {assert.equal(await prepareLegacySchema(h.args),false);assert.ok(!h.calls.includes('backup'));}
});
test('known desktop V43 is backed up before transactional history mapping',async()=>{
 const h=harness([['43','V43__operation_systems.sql',String(LEGACY_CHECKSUM),'1']]);assert.equal(await prepareLegacySchema(h.args),true);
 assert.ok(h.calls.indexOf('backup')<h.calls.findIndex(sql=>typeof sql==='string'&&sql.startsWith('START TRANSACTION')));assert.ok(h.calls.includes('history-mapped'));
});
test('unknown checksums and duplicate target versions stop without mutation',async()=>{
 for(const rows of [[['43','V43__operation_systems.sql','999','1']],[['43','V43__operation_systems.sql',String(LEGACY_CHECKSUM),'1'],['44','unexpected.sql','1','1']]]) {const h=harness(rows);await assert.rejects(prepareLegacySchema(h.args));assert.ok(!h.calls.includes('backup'));}
});
test('missing business tables stop without mutation',async()=>{const h=harness([['43','V43__operation_systems.sql',String(LEGACY_CHECKSUM),'1']],{tables:'1'});await assert.rejects(prepareLegacySchema(h.args));assert.ok(!h.calls.includes('backup'));});
test('failed backup cannot change history',async()=>{const h=harness([['43','V43__operation_systems.sql',String(LEGACY_CHECKSUM),'1']],{backupFails:true});await assert.rejects(prepareLegacySchema(h.args));assert.ok(!h.calls.some(sql=>typeof sql==='string'&&sql.startsWith('START TRANSACTION')));});
test('interrupted bridge resumes and completed main V44 is idempotent',async()=>{
 const row=['44','V44__operation_systems.sql',String(MAIN_CHECKSUM),'1'];const pending=harness([row]);assert.equal(await prepareLegacySchema(pending.args),true);assert.ok(!pending.calls.includes('backup'));
 const done=harness([row,['43','V43__account_show_guides.sql','12','1']]);assert.equal(await prepareLegacySchema(done.args),false);assert.ok(!done.calls.includes('backup'));
 const failed=harness([['44','V44__operation_systems.sql',String(MAIN_CHECKSUM),'0']]);assert.equal(await prepareLegacySchema(failed.args),false);assert.ok(!failed.calls.includes('backup'));
});
