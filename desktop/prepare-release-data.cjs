// Upgrade/export an owned COPY of an existing profile; never start its original DB.
// Before use, copy service to <release>/dataset-profile/service and save the source
// file manifest as <release>/source-files-before.json (see release verification).
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const { Database, fileManifest, hash, startBackend, writeJson } = require('./final-db-tools.cjs');
const { assertPreserved } = require('./merge-final-db.cjs');
const root = path.resolve(__dirname, '..');
const release = path.resolve(process.argv[2] || '');
const source = path.resolve(process.argv[3] || '');
if (!release.startsWith(path.join(root, 'output') + path.sep) || !process.argv[3]) throw Error('Use an owned output directory and explicit read-only source service path');
const service = path.join(release, 'dataset-profile/service');
const evidence = path.join(release, 'data-evidence');
const bootstrap = path.join(release, 'bootstrap');
if (fs.existsSync(bootstrap)) throw Error('Use a fresh bootstrap destination');
fs.mkdirSync(evidence, { recursive: true });
const bundle = path.join(root, 'output/OMNIS-Final-Database-Windows-x64');
const war = path.join(root, 'src/backend/build/libs/ictwork-0.0.1.war');
const db = new Database({ bundle, service, evidence });
(async () => {
  try {
    await db.start();
    const before = db.snapshot();
    writeJson(path.join(evidence, 'before-rows.json'), before);
    const backend = await startBackend(db, { bundle, war, database: 'ictwork', cwd: service,
      logFile: path.join(evidence, 'migration.log'), extraEnv: { ICTWORK_AI_ENABLED: 'true', ICTWORK_AI_PROVIDER: 'LOCAL' } });
    await backend.stop();
    const after = db.snapshot();
    assertPreserved(before, after);
    assert.deepEqual(db.foreignKeyErrors(), []);
    assert.equal(db.sql('SELECT COUNT(*) FROM stored_file f JOIN stored_file_body b ON b.file_id=f.id WHERE f.sha256<>SHA2(b.data,256) OR f.size<>OCTET_LENGTH(b.data)'), '0');
    writeJson(path.join(evidence, 'after-rows.json'), after);
    fs.mkdirSync(bootstrap);
    const sql = await db.dump('ictwork', path.join(bootstrap, 'final-db.sql'));
    const config = fs.readFileSync(path.join(service, 'config/application.yml'), 'utf8');
    const loginId = config.match(/^\s*login-id:\s*["']?([^\s"']+)["']?\s*$/m)?.[1];
    const passwordHash = config.match(/^\s*password-hash:\s*["'](\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53})["']\s*$/m)?.[1];
    assert.ok(loginId && passwordHash, 'Preserve actual QA credentials, never guess or replace them');
    const qa = `ictwork:\n  qa-master:\n    login-id: ${JSON.stringify(loginId)}\n    password-hash: ${JSON.stringify(passwordHash)}\n`;
    fs.writeFileSync(path.join(bootstrap, 'qa-config.yml'), qa);
    const commit = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
    const manifest = { format: 1, createdAt: new Date().toISOString(), sourceCommit: commit,
      defaultProfile: 'OMNIS Desktop Final', warSha256: hash(fs.readFileSync(war)),
      sql: { bytes: sql.bytes, sha256: sql.sha256 }, qaConfig: { loginId, sha256: hash(qa), existingCredentialsPreserved: true },
      tables: Object.fromEntries(Object.entries(after).map(([table, info]) => [table, { count: info.count, sha256: info.sha256 }])),
      sourceRowsPreserved: true, seedPolicy: 'Existing Final profile copy; additive current-source migrations only' };
    writeJson(path.join(bootstrap, 'manifest.json'), manifest);
    writeJson(path.join(evidence, 'result.json'), { success: true, previousTables: Object.keys(before).length,
      currentTables: Object.keys(after).length, sourceRowsPreserved: true, foreignKeysValid: true, attachmentsValid: true,
      flyway: db.sql('SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1'),
      counts: Object.fromEntries(['account','equipment','operation_system','work_request','work_item','calendar_event'].map(table => [table, after[table].count])) });
    console.log('PASS copied profile migration, all original row fingerprints, FK and attachment checks; bootstrap exported');
  } finally {
    await db.stop();
    assert.deepEqual(fileManifest(source), JSON.parse(fs.readFileSync(path.join(release, 'source-files-before.json'), 'utf8')), 'Original profile must remain byte-for-byte unchanged');
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
