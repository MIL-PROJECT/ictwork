const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { ident, literal, hash, writeJson, sceneHashes } = require('./final-db-tools.cjs');

function metadata(db, schema) {
  const tables = {};
  for (const [table, name, type, extra, max] of db.rows(`SELECT table_name,column_name,data_type,extra,COALESCE(character_maximum_length,0) FROM information_schema.columns WHERE table_schema=${literal(schema)} ORDER BY table_name,ordinal_position`)) {
    (tables[table] ??= { columns: [], pk: [], fk: [] }).columns.push({ name, type, generated: /(?:STORED|VIRTUAL) GENERATED/.test(extra), max: Number(max) });
  }
  for (const [table, col, parent, parentCol, constraint] of db.rows(`SELECT table_name,column_name,COALESCE(referenced_table_name,''),COALESCE(referenced_column_name,''),constraint_name FROM information_schema.key_column_usage WHERE table_schema=${literal(schema)} ORDER BY table_name,constraint_name,ordinal_position`)) {
    if (constraint === 'PRIMARY') tables[table].pk.push(col);
    if (parent) tables[table].fk.push({ col, parent, parentCol, constraint });
  }
  return tables;
}
const binary = type => /blob|binary|bit/.test(type);
function readRows(db, table, meta, schema) {
  const object = meta.columns.flatMap(col => [literal(col.name), binary(col.type) ? `HEX(${ident(col.name)})` : `CAST(${ident(col.name)} AS CHAR CHARACTER SET utf8mb4)`]).join(',');
  return db.rows(`SELECT HEX(JSON_OBJECT(${object})) FROM ${ident(schema)}.${ident(table)}`+(meta.pk.length?' ORDER BY '+meta.pk.map(ident).join(','):'' )).map(([hex]) => JSON.parse(Buffer.from(hex, 'hex').toString('utf8')));
}
const key = (row, cols) => JSON.stringify(cols.map(col => row[col]));
const folded = value => value?.normalize('NFC').toLocaleLowerCase('en-US');
function remapFiles(value, map) {
  if (Array.isArray(value)) return value.map(item => remapFiles(item, map));
  if (typeof value === 'number') return Number(map(value));
  if (typeof value === 'string' && /^\d+$/.test(value)) return map(value);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key,item]) => [key, ['id','fileId','files','fileIds'].includes(key) ? remapFiles(item,map) : item]));
  return value;
}
function assertPreserved(before, after) {
  for (const [table, info] of Object.entries(before)) {
    const available = new Set(after[table]?.rows || []);
    for (const digest of info.rows) assert.ok(available.has(digest), 'An original row changed or disappeared: ' + table);
  }
}
function buildMerge(db) {
  const meta = metadata(db, 'final_seed');
  const originalMeta = metadata(db, 'ictwork');
  assert.deepEqual(originalMeta, meta, 'Source and seed schemas must match');
  const source = {}, donor = {}, maps = {}, shared = {}, notes = [];
  for (const [table, info] of Object.entries(meta)) {
    source[table] = readRows(db, table, info, 'ictwork');
    donor[table] = readRows(db, table, info, 'final_seed');
    if (info.pk.length === 1 && info.pk[0] === 'id') {
      const max = [...source[table], ...donor[table]].reduce((n, row) => BigInt(row.id) > n ? BigInt(row.id) : n, 0n);
      maps[table] = new Map(donor[table].map((row, index) => [row.id, String(max + 1000000n + BigInt(index + 1))]));
      shared[table] = new Set();
    }
  }
  const map = (table, id) => id == null ? null : maps[table]?.get(String(id)) ?? String(id);
  function share(table, find) {
    for (const row of donor[table]) {
      const matches = source[table].filter(existing => find(row, existing));
      assert.ok(matches.length <= 1, 'Ambiguous natural identity in ' + table);
      if (matches.length) { maps[table].set(row.id, matches[0].id); shared[table].add(row.id); }
    }
  }
  // Main seed department IDs are explicit and stable; legacy names are retained.
  // Require the full parent/type lineage to match before accepting renamed rows.
  share('department', (row, old) => row.id === old.id && row.type === old.type && row.parent_id === old.parent_id);
  for (const row of donor.department.filter(row => shared.department.has(row.id))) {
    const old = source.department.find(item => item.id === row.id);
    if (old.name !== row.name) notes.push({ table: 'department', id: row.id, policy: 'existing-name-preserved', seedName: row.name, existingName: old.name });
  }
  share('account', (row, old) => folded(row.login_id) === folded(old.login_id));
  const accountPeople = new Map();
  for (const row of donor.account) {
    const old = source.account.find(item => item.id === map('account', row.id));
    if (row.personnel_id && old?.personnel_id) accountPeople.set(row.personnel_id, old.personnel_id);
  }
  share('personnel', (row, old) => accountPeople.has(row.id) ? accountPeople.get(row.id) === old.id : row.id === old.id && row.name === old.name);
  // One account per person is enforced by main. Preserve an existing renamed
  // login instead of creating a second account for that same person.
  share('account', (row, old) => folded(row.login_id) === folded(old.login_id) || (row.personnel_id != null && map('personnel', row.personnel_id) === old.personnel_id));
  for (const row of donor.account.filter(row => shared.account.has(row.id))) {
    const old = source.account.find(old => old.id === map('account', row.id));
    if (old.login_id !== row.login_id) notes.push({ table: 'account', policy: 'existing-login-preserved', existingLogin: old.login_id, seedLogin: row.login_id });
  }
  share('location', (row, old) => folded(row.name) === folded(old.name) && row.active === old.active);
  share('equipment', (row, old) => folded(row.model_name) === folded(old.model_name) && folded(row.serial_number) === folded(old.serial_number));
  share('work_type', (row, old) => map('department', row.department_id) === old.department_id && (row.is_other === '1' ? old.is_other === '1' : row.name === old.name));
  share('approval_route', (row, old) => map('department', row.department_id) === old.department_id && row.doc_type === old.doc_type);
  share('attribute_def', (row, old) => row.target === old.target && map('department', row.department_id) === old.department_id && row.name === old.name && row.kind === old.kind && row.config === old.config);
  share('work_form_version', (row, old) => map('work_type', row.work_type_id) === old.work_type_id && row.definition === old.definition);
  share('role_assignment', (row, old) => map('account', row.account_id) === old.account_id && map('department', row.department_id) === old.department_id && row.role_code === old.role_code);
  const translateColumn = (table, col, value, visited = new Set()) => {
    if (value == null) return null;
    if (col === 'id' && maps[table]) return map(table, value);
    const edge = meta[table].fk.find(fk => fk.col === col);
    if (!edge) return value;
    const marker = table + '.' + col;
    assert.ok(!visited.has(marker), 'Cyclic foreign-key column mapping: ' + marker);
    visited.add(marker);
    return translateColumn(edge.parent, edge.parentCol, value, visited);
  };
  const transformed = {};
  const counts = {};
  const skip = table => table === 'flyway_schema_history' || /^spring_session/i.test(table);
  for (const [table, info] of Object.entries(meta)) {
    transformed[table] = [];
    for (const row of donor[table]) {
      if (skip(table) || shared[table]?.has(row.id)) continue;
      const next = Object.fromEntries(info.columns.filter(col => !col.generated).map(col => [col.name, translateColumn(table, col.name, row[col.name])]));
      next.__old = row;
      transformed[table].push(next);
    }
  }
  function uniqueName(table, row, nameCol, keyCol, scopeCols) {
    const originalName = row[nameCol], max = meta[table].columns.find(col => col.name === nameCol).max;
    const existing = [...source[table], ...transformed[table].filter(other => other !== row)];
    const collides = () => existing.some(other => scopeCols.every(col => row[col] === other[col]) && folded(row[nameCol]) === folded(other[nameCol]));
    let index = 1;
    while (collides()) { const suffix = index === 1 ? ' ·시연' : ' ·시연' + index; row[nameCol] = [...originalName].slice(0, max - suffix.length).join('') + suffix; index++; }
    if (keyCol) row[keyCol] = folded(row[nameCol]);
  }
  for (const row of transformed.diagram) uniqueName('diagram', row, 'name', null, ['owner_department_id']);
  for (const row of transformed.ict_box) uniqueName('ict_box', row, 'title', 'title_key', ['department_id']);
  for (const row of transformed.duty_roster) uniqueName('duty_roster', row, 'name', 'name_key', ['department_id']);
  for (const row of transformed.scope_card) uniqueName('scope_card', row, 'name', null, ['account_id']);
  for (const row of transformed.attribute_def) uniqueName('attribute_def', row, 'name', null, ['target', 'department_id']);
  // A shared work type may already have version 1 with a different definition.
  const nextVersions = new Map();
  for (const row of transformed.work_form_version) {
    const used = source.work_form_version.filter(old => old.work_type_id === row.work_type_id).map(old => Number(old.version_no));
    const current = nextVersions.get(row.work_type_id) ?? Math.max(0, ...used);
    row.version_no = String(Math.max(Number(row.version_no), current + 1)); nextVersions.set(row.work_type_id, Number(row.version_no));
  }
  const rewriteLink = text => text?.replace(/\/approvals\/docs\/(\d+)/g, (_, id) => '/approvals/docs/' + map('approval_doc', id)).replace(/\/(requests|items|diagrams|directives)\/(\d+)/g, (_, kind, id) => '/' + kind + '/' + map({ requests: 'work_request', items: 'work_item', diagrams: 'diagram', directives: 'directive' }[kind], id));
  const recursiveIds = value => {
    if (Array.isArray(value)) return value.map(recursiveIds);
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, item]) => {
      const table = { equipmentId: 'equipment', personnelId: 'personnel', accountId: 'account', departmentId: 'department', fileId: 'stored_file', requestId: 'work_request', workItemId: 'work_item' }[key];
      if (['files','fileIds'].includes(key) && Array.isArray(item)) return [key, item.map(file => typeof file === 'number' || typeof file === 'string' ? Number(map('stored_file',file)) : recursiveIds(file))];
      return [key, table && item != null ? (typeof item === 'number' ? Number(map(table, item)) : map(table, item)) : recursiveIds(item)];
    }));
    return value;
  };
  for (const row of transformed.notification) row.link = rewriteLink(row.link);
  for (const row of transformed.request_target_type) if(row.answers) row.answers=JSON.stringify(recursiveIds(JSON.parse(row.answers)));
  for(const file of donor.request_answer_file){
    const row=transformed.request_target_type.find(row=>row.id===map('request_target_type',file.request_target_type_id));
    assert.ok(row,'Imported form attachment must have an imported answer');
    const answers=JSON.parse(row.answers);
    // Use the original answer to avoid applying an ID map twice.
    answers[file.question_key]=remapFiles(JSON.parse(row.__old.answers)[file.question_key],id=>map('stored_file',id));
    row.answers=JSON.stringify(answers);
  }
  for (const row of transformed.diagram_version_scene) {
    row.scene = JSON.stringify(recursiveIds(JSON.parse(row.scene)));
  }
  const canonicalHashes=sceneHashes(transformed.diagram_version_scene.map(row=>row.scene));
  for (const [index,row] of transformed.diagram_version_scene.entries()) {
    const version = transformed.diagram_version.find(version => version.id === row.version_id);
    assert.ok(version, 'Every imported scene must have an imported version'); version.scene_hash = canonicalHashes[index];
  }
  for (const table of ['ict_box', 'ict_box_revision']) for (const row of transformed[table]) {
    row.schema_id = map('ict_box_schema', row.__old.schema_id);
    row.tables_json = JSON.stringify(Object.fromEntries(Object.entries(JSON.parse(row.tables_json)).map(([key, value]) => [key, Number(map('ict_box_table', value))])));
    row.values_json = JSON.stringify(recursiveIds(JSON.parse(row.values_json)));
  }
  for (const row of transformed.attribute_value_file) row.owner_id = map(row.target === 'EQUIPMENT' ? 'equipment' : 'personnel', row.__old.owner_id);
  for (const table of ['equipment', 'personnel']) for (const row of transformed[table]) if (row.attr_values) {
    row.attr_values = JSON.stringify(Object.fromEntries(Object.entries(JSON.parse(row.attr_values)).map(([id, value]) => [map('attribute_def', id), donor.attribute_def.find(def=>def.id===id)?.kind==='FILE' ? remapFiles(value,id=>map('stored_file',id)) : recursiveIds(value)])));
  }
  // Give seed document trees a dedicated root per department, avoiding duplicate
  // root filenames and preserving all source document names/parents.
  const folders = new Map();
  let folderId = [...source.doc_node, ...transformed.doc_node].reduce((n, row) => BigInt(row.id) > n ? BigInt(row.id) : n, 0n) + 1n;
  for (const row of [...transformed.doc_node]) if (row.parent_id == null) {
    if (!folders.has(row.department_id)) {
      const folder = { ...row, id: String(folderId++), parent_id: null, kind: 'FOLDER', name: '최신 main 시연 자료', file_id: null, size: '0', trashed_at: null, trashed_by_account_id: null, __old: {} };
      transformed.doc_node.push(folder); folders.set(row.department_id, folder.id);
    }
    row.parent_id = folders.get(row.department_id);
  }
  // De-duplicate only rows with a shared primary identity. New business roots
  // always have a separate ID, so their children cannot attach to old requests.
  for (const [table, rows] of Object.entries(transformed)) {
    const existingKeys = new Set(source[table].map(row => key(row, meta[table].pk)));
    transformed[table] = rows.filter(row => {
      if (!meta[table].pk.length) { assert.equal(rows.length, 0, 'Cannot import a table without a primary key'); return false; }
      const pk = key(row, meta[table].pk);
      if (existingKeys.has(pk)) return false;
      existingKeys.add(pk); return true;
    });
    counts[table] = { before: source[table].length, seed: donor[table].length, imported: transformed[table].length, after: source[table].length + transformed[table].length };
  }
  // Main requires globally unique active interface MAC addresses. Keep every
  // original address and give only colliding newly imported synthetic interfaces
  // a deterministic, locally administered address (02-FE-*).
  const occupiedMacs = new Set(source.equipment_network.map(row => row.active_mac_key).filter(Boolean));
  let macSequence=1;
  for(const row of transformed.equipment_network) if(row.active_mac_key){
    if(occupiedMacs.has(row.active_mac_key)){
      const old=row.mac;let mac;
      do{mac='02fe'+(macSequence++).toString(16).padStart(8,'0');}while(occupiedMacs.has(mac));
      row.mac_key=mac;row.active_mac_key=mac;row.mac=mac.match(/../g).join('-').toUpperCase();
      notes.push({table:'equipment_network',policy:'synthetic-MAC-conflict-remapped',equipmentId:row.equipment_id,originalSeedMac:old,finalMac:row.mac});
    }
    occupiedMacs.add(row.active_mac_key);
  }
  const sqlValue = (value, col) => value == null ? 'NULL' : binary(col.type) ? "X'" + value + "'" : "CONVERT(X'" + Buffer.from(String(value), 'utf8').toString('hex') + "' USING utf8mb4)";
  const statements = ['SET NAMES utf8mb4;', 'SET FOREIGN_KEY_CHECKS=0;', 'START TRANSACTION;', 'CREATE TEMPORARY TABLE final_merge_assert(ok INT NOT NULL CHECK(ok=1));'];
  for (const [table, rows] of Object.entries(transformed)) {
    const columns = meta[table].columns.filter(col => !col.generated);
    for (let start = 0; start < rows.length; start += 100) {
      statements.push(`INSERT INTO ${ident(table)} (${columns.map(col => ident(col.name)).join(',')}) VALUES\n` + rows.slice(start, start + 100).map(row => '(' + columns.map(col => sqlValue(row[col.name], col)).join(',') + ')').join(',\n') + ';');
    }
  }
  // CHECK violations abort this CLI connection and roll the transaction back.
  for (const [table, info] of Object.entries(meta)) {
    const groups = new Map();
    for (const fk of info.fk) { if (!groups.has(fk.constraint)) groups.set(fk.constraint, []); groups.get(fk.constraint).push(fk); }
    for (const cols of groups.values()) {
      const join = cols.map(fk => `c.${ident(fk.col)}=p.${ident(fk.parentCol)}`).join(' AND ');
      const nonnull = cols.map(fk => `c.${ident(fk.col)} IS NOT NULL`).join(' AND ');
      statements.push(`INSERT INTO final_merge_assert SELECT IF(EXISTS(SELECT 1 FROM ${ident(table)} c LEFT JOIN ${ident(cols[0].parent)} p ON ${join} WHERE ${nonnull} AND p.${ident(cols[0].parentCol)} IS NULL),0,1);`);
    }
  }
  statements.push('SET FOREIGN_KEY_CHECKS=1;', 'COMMIT;');
  return { sql: statements.join('\n'), counts, notes, maps: Object.fromEntries(Object.entries(maps).map(([table, map]) => [table, Object.fromEntries(map)])), transformed, donor, source, meta };
}
module.exports = { buildMerge, assertPreserved, metadata, readRows, remapFiles };
