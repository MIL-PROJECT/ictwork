const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path'), crypto = require('node:crypto');
const { installFinalDataset } = require('./final-dataset.cjs');
function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'omnis-final-dataset-'));
  t.after(() => { assert.ok(path.resolve(root).startsWith(path.resolve(os.tmpdir())+path.sep+'omnis-final-dataset-')); fs.rmSync(root, { recursive: true, force: true }); });
  const directory = path.join(root, 'dataset'), state = path.join(root, 'service');
  fs.mkdirSync(directory); fs.mkdirSync(state);
  const sql = 'SELECT 1;\n'; fs.writeFileSync(path.join(directory, 'final-db.sql'), sql);
  const manifest = { format: 1, sourceCommit: 'fixture', sql: { sha256: crypto.createHash('sha256').update(sql).digest('hex') }, tables: { account: { count: 62 } } };
  fs.writeFileSync(path.join(directory, 'manifest.json'), JSON.stringify(manifest));
  const record = async (file, value) => fs.writeFileSync(file, JSON.stringify(value));
  return { directory, state, manifest, record };
}
test('initializes a fresh DB, verifies row counts, and never reimports after user edits', async t => {
  const f = fixture(t); let restores = 0;
  const options = { ...f, query: async sql => [[sql.includes('information_schema') ? '0' : '62']], restore: async () => { restores++; } };
  assert.equal((await installFinalDataset(options)).installed, true);
  assert.equal((await installFinalDataset({ ...options, query: async () => { throw Error('Do not inspect or overwrite user data'); } })).reason, 'already-installed');
  assert.equal(restores, 1);
});
test('preserves every existing DB, including a partially populated one', async t => {
  const f = fixture(t);
  assert.equal((await installFinalDataset({ ...f, query: async () => [['1']], restore: async () => assert.fail('Existing DB must not be restored') })).reason, 'existing-database-preserved');
  assert.deepEqual(fs.readdirSync(f.state), []);
});
test('rejects a changed SQL file before recording or restoring anything', async t => {
  const f = fixture(t); fs.appendFileSync(path.join(f.directory, 'final-db.sql'), 'corrupt');
  await assert.rejects(installFinalDataset({ ...f, query: async () => [['0']], restore: async () => assert.fail() }), /해시/);
  assert.deepEqual(fs.readdirSync(f.state), []);
});
test('keeps an interruption marker and refuses to overwrite a partial restore', async t => {
  const f = fixture(t);
  await assert.rejects(installFinalDataset({ ...f, query: async () => [['0']], restore: async () => { throw Error('disk full'); } }), /disk full/);
  await assert.rejects(installFinalDataset({ ...f, query: async () => assert.fail(), restore: async () => assert.fail() }), /중단/);
});
test('rejects a restored dataset with an unexpected number of rows', async t => {
  const f = fixture(t);
  await assert.rejects(installFinalDataset({ ...f, query: async sql => [[sql.includes('information_schema') ? '0' : '61']], restore: async () => {} }), /건수/);
  assert.equal(fs.existsSync(path.join(f.state, 'final-dataset-installed.json')), false);
});
