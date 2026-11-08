// Build a separate final database; the source service is only read/copied/hashed.
// Usage: node desktop/prepare-final-db.cjs inspect <new-output-directory> [bundle]
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const { Database, startBackend, fileManifest, writeJson, ident, literal } = require('./final-db-tools.cjs');
const [phase, outputArg, bundleArg] = process.argv.slice(2);
const root = path.resolve(__dirname, '..');
const output = path.resolve(outputArg || '');
const bundle = path.resolve(bundleArg || path.join(root, 'output/OMNIS-Main-Merged-Windows-x64'));
const war = path.join(root, 'src/backend/build/libs/ictwork-0.0.1.war');
const source = path.join(process.env.APPDATA, 'OMNIS Desktop Main/service');
const service = path.join(output, 'final-profile/service');
const evidence = path.join(output, 'evidence');
const donor = 'final_seed';
function audit(db) {
  return db.tables(donor).map(table => {
    const pk = db.primaryKey(table, donor), cols = db.columns(table, donor);
    const count = Number(db.sql(`SELECT COUNT(*) FROM ${ident(donor)}.${ident(table)}`));
    const existing = Number(db.sql(`SELECT COUNT(*) FROM ictwork.${ident(table)}`));
    if (!count || !pk.length || table === 'flyway_schema_history' || table.startsWith('SPRING_SESSION')) return { table, source: existing, seed: count, excluded: true };
    const join = pk.map(col => `s.${ident(col)} <=> d.${ident(col)}`).join(' AND ');
    const missing = Number(db.sql(`SELECT COUNT(*) FROM ${ident(donor)}.${ident(table)} d WHERE NOT EXISTS(SELECT 1 FROM ictwork.${ident(table)} s WHERE ${join})`));
    const differences = cols.map(col => `NOT (BINARY s.${ident(col)} <=> BINARY d.${ident(col)})`).join(' OR ');
    const different = Number(db.sql(`SELECT COUNT(*) FROM ${ident(donor)}.${ident(table)} d JOIN ictwork.${ident(table)} s ON ${join} WHERE ${differences}`));
    const changedColumns = different ? cols.map(col => ({ column: col, rows: Number(db.sql(`SELECT COUNT(*) FROM ${ident(donor)}.${ident(table)} d JOIN ictwork.${ident(table)} s ON ${join} WHERE NOT (BINARY s.${ident(col)} <=> BINARY d.${ident(col)})`)) })).filter(item => item.rows) : [];
    return { table, source: existing, seed: count, missing, existingDifferent: different, changedColumns };
  });
}
async function inspect() {
  assert.ok(outputArg, 'Specify a new output directory');
  assert.ok(output.startsWith(path.join(root, 'output') + path.sep), 'Output must be in this repository output directory');
  const resuming = fs.existsSync(output);
  assert.ok(!fs.existsSync(path.join(output, 'final-db-manifest.json')), 'A finalized dataset must not be inspected again');
  // Other development databases may stay running. Unknown/defaults-file instances
  // are rejected because their data directory cannot be proved independent.
  const processJson = execFileSync('powershell.exe', ['-NoProfile', '-Command', "ConvertTo-Json -InputObject @(Get-CimInstance Win32_Process -Filter \"Name='mysqld.exe'\" | Select-Object ProcessId,CommandLine)"], { encoding: 'utf8', windowsHide: true }).trim();
  for (const process of JSON.parse(processJson || '[]')) {
    const match = process.CommandLine?.match(/--datadir=(?:"([^"]+)"|(\S+))/);
    assert.ok(match, 'Close the OMNIS/defaults-file MySQL instance before a cold snapshot');
    assert.notEqual(path.resolve(match[1] || match[2]).toLowerCase(), path.join(source, 'mysql-data').toLowerCase(), 'Close the source MySQL before a cold snapshot');
  }
  fs.mkdirSync(evidence, { recursive: true });
  console.log('Hashing and copying the closed source database (source remains unchanged)');
  const before = fileManifest(source);
  if (resuming) assert.deepEqual(JSON.parse(fs.readFileSync(path.join(evidence, 'source-files-before.json'), 'utf8')), before, 'Source changed since the first snapshot');
  else writeJson(path.join(evidence, 'source-files-before.json'), before);
  const backup = path.join(output, 'source-backup/service');
  if (!resuming) fs.cpSync(source, backup, { recursive: true, errorOnExist: true, force: false });
  assert.deepEqual(fileManifest(backup), before, 'Cold backup must match source bytes');
  assert.deepEqual(fileManifest(source), before, 'Source changed during cold backup');
  fs.mkdirSync(path.dirname(service), { recursive: true });
  if (!resuming) fs.cpSync(backup, service, { recursive: true, errorOnExist: true, force: false });
  const commit = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
  writeJson(path.join(output, 'build-inputs.json'), { commit, sourceProfile: path.dirname(source), bundle, war, sourceFiles: before.length, createdAt: new Date().toISOString() });
  const db = new Database({ bundle, service, evidence });
  let backend;
  try {
    await db.start();
    console.log('Copied MySQL ready; capturing all source tables');
    const original = db.snapshot();
    writeJson(path.join(evidence, 'source-rows.json'), original);
    const originalDump = path.join(output, 'original-db.sql');
    if (fs.existsSync(originalDump)) {
      assert.equal(fs.statSync(originalDump).size, 0, 'A completed original export already exists');
      fs.unlinkSync(originalDump);
    }
    writeJson(path.join(evidence, 'source-dump.json'), await db.dump('ictwork', originalDump));
    assert.deepEqual(db.foreignKeyErrors(), [], 'Source foreign key consistency');
    db.sql(`CREATE DATABASE ${ident(donor)} CHARACTER SET utf8mb4;`);
    console.log('Creating a fresh donor database with main Flyway migrations and demo seeds');
    backend = await startBackend(db, { bundle, war, database: donor, cwd: service, logFile: path.join(evidence, 'seed-spring.log') });
    await backend.stop(); backend = undefined;
    assert.equal(db.sql(`SELECT MAX(CAST(version AS UNSIGNED)) FROM ${donor}.flyway_schema_history WHERE success=1`), '44');
    assert.deepEqual(db.foreignKeyErrors(donor), [], 'Fresh seed foreign key consistency');
    writeJson(path.join(evidence, 'seed-dump.json'), await db.dump(donor, path.join(output, 'main-seed.sql')));
    const report = audit(db);
    writeJson(path.join(evidence, 'merge-audit.json'), report);
    writeJson(path.join(evidence, 'seed-rows.json'), db.snapshot(donor));
    console.log(JSON.stringify(report.filter(item => item.seed || item.source).map(({ changedColumns, ...item }) => ({ ...item, changedColumns: changedColumns?.map(column => column.column) })), null, 2));
  } finally {
    if (backend) await backend.stop();
    await db.stop();
    assert.deepEqual(fileManifest(source), before, 'Original source must remain byte-for-byte unchanged');
  }
  console.log('Inspection complete; original DB and fresh main seed are independently backed up');
}
if (require.main === module) {
  (phase === 'inspect' ? inspect() : Promise.reject(Error('Supported phase: inspect'))).catch(error => { console.error(error.message); process.exitCode = 1; });
}
module.exports = { audit };
