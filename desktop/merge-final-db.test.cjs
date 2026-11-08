const{test}=require('node:test');const assert=require('node:assert/strict');const{remapFiles,assertPreserved}=require('./merge-final-db.cjs');
test('remaps flat file answers and file objects without changing metadata',()=>{
 const convert=id=>String(Number(id)+1000000);
 assert.deepEqual(remapFiles([100001],convert),[1100001]);
 assert.deepEqual(remapFiles({id:100001,name:'photo.png',size:100001},convert),{id:1100001,name:'photo.png',size:100001});
 assert.deepEqual(remapFiles({fileIds:[100001,'100002']},convert),{fileIds:[1100001,'1100002']});
});
test('row-preservation validation rejects a changed or missing original row',()=>{
 const before={account:{rows:['a','b']}};
 assert.doesNotThrow(()=>assertPreserved(before,{account:{rows:['b','c','a']}}));
 assert.throws(()=>assertPreserved(before,{account:{rows:['a','changed']}}),/original row/);
});
