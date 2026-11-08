// Isolated runtime for API/browser/Gradle validation. Never opens an installed user profile.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const { pathToFileURL } = require('node:url');
const { setTimeout: delay } = require('node:timers/promises');
const { _electron } = require('playwright');
const { Database, literal } = require('./final-db-tools.cjs');
const net = require('node:net');
async function freePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
const root = path.resolve(__dirname, '..');
const evidence = path.join(root, 'output/source-validation', new Date().toISOString().replace(/[:.]/g, '-'));
const profile = path.join(evidence, 'profile');
const bundle = path.join(root, 'output/OMNIS-Final-Database-Windows-x64');
let app, vite, closing = false;
const keepAlive = setInterval(() => {}, 10000);
async function close() {
  if (closing) return;
  closing = true;
  clearInterval(keepAlive);
  try { if (app) await app.close(); } finally { if (vite) await vite.close(); }
}
(async () => {
  fs.mkdirSync(evidence, { recursive: true });
  const sourceWar = path.join(evidence, 'source.war');
  fs.copyFileSync(path.join(root, 'src/backend/build/libs/ictwork-0.0.1.war'), sourceWar);
  const password = crypto.randomBytes(24).toString('base64url') + '!7a';
  const qaPassword = crypto.randomBytes(24).toString('base64url') + '!7a';
  const bcrypt = value => execFileSync('python.exe', ['-c', 'import sys,bcrypt;sys.stdout.write(bcrypt.hashpw(sys.stdin.buffer.read(),bcrypt.gensalt(rounds=10)).decode())'], { input: value, encoding: 'utf8', windowsHide: true }).trim();
  const port = await freePort();
  const frontend = path.join(root, 'src/frontend');
  const { createServer } = await import(pathToFileURL(require.resolve('vite', { paths: [frontend] })).href);
  vite = await createServer({ root: frontend, configFile: path.join(frontend, 'vite.config.ts'), server: { host: '127.0.0.1', port: 5184, strictPort: true, proxy: { '/api': 'http://127.0.0.1:' + port } } });
  await vite.listen();
  const env = { ...process.env, OMNIS_DEV_RESOURCES: path.join(bundle, 'resources'), OMNIS_DEV_WAR: sourceWar, OMNIS_DEV_URL: 'http://localhost:5184', OMNIS_DEV_BACKEND_PORT: String(port), OMNIS_DEV_HEADLESS: '1', ICTWORK_AI_ENABLED: 'true', ICTWORK_AI_PROVIDER: 'LOCAL', ICTWORK_SCHEDULER_ENABLED: 'false', ICTWORK_QAMASTER_LOGINID: 'source-validation-master', ICTWORK_QAMASTER_PASSWORDHASH: bcrypt(qaPassword) };
  delete env.ELECTRON_RUN_AS_NODE;
  app = await _electron.launch({ executablePath: process.env.OMNIS_ELECTRON_EXE || path.join(root, '../military_project/node_modules/electron/dist/electron.exe'), args: [__dirname, '--omnis-dev', '--user-data-dir=' + profile], env, timeout: 60000 });
  app.process().on('exit', code => console.log('Validation Electron exit: ' + code));
  let ready = false;
  for (let attempt = 0; attempt < 480; attempt++) {
    try { if ((await fetch('http://127.0.0.1:' + port + '/signin', { signal: AbortSignal.timeout(700) })).ok) { ready = true; break; } } catch { /* startup */ }
    await delay(500);
  }
  if (!ready) throw Error('Source validation backend did not become ready; see ' + path.join(profile, 'service/spring.log'));
  const db = new Database({ bundle, service: path.join(profile, 'service'), evidence });
  db.port = Number(fs.readFileSync(path.join(profile, 'service/omnis-mysql.ini'), 'utf8').match(/^port=(\d+)$/m)[1]);
  if (!db.rows("SELECT id FROM account WHERE login_id='sysadmin'").length) throw Error('Expected sysadmin fixture is missing');
  db.sql("UPDATE account SET password_hash=" + literal(bcrypt(password)) + ",password_change_required=FALSE WHERE login_id IN ('sysadmin','bn1-co1-admin')");
  const schema = 'omnis_source_test_' + Date.now();
  db.sql('CREATE DATABASE `' + schema + '` CHARACTER SET utf8mb4; GRANT ALL ON `' + schema + "`.* TO 'ictwork'@'127.0.0.1';");
  const fixture = { evidence, profile, origin: 'http://127.0.0.1:' + port, frontend: 'http://localhost:5184', mysqlPort: db.port, schema, databaseUser: 'ictwork', databasePassword: db.credentials.app, adminLogin: 'sysadmin', adminPassword: password, qaLogin: 'source-validation-master', qaPassword };
  fs.writeFileSync(path.join(evidence, 'fixture.json'), JSON.stringify(fixture, null, 2));
  fs.writeFileSync(path.join(root, 'output/source-validation/latest.json'), JSON.stringify({ evidence, fixture: path.join(evidence, 'fixture.json') }, null, 2));
  console.log('SOURCE VALIDATION READY ' + fixture.frontend + ' · evidence: ' + evidence);
  const readline = require('node:readline').createInterface({ input: process.stdin });
  readline.on('line', line => { if (line.trim() === 'quit') { readline.close(); void close(); } });
  app.on('close', () => { app = undefined; readline.close(); void close(); });
})().catch(async error => { console.error(error); process.exitCode = 1; await close(); });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { void close(); });
