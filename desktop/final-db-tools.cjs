const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const net = require('node:net');
const { spawn, execFileSync } = require('node:child_process');
const { pipeline } = require('node:stream/promises');
const { setTimeout: delay } = require('node:timers/promises');

const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const ident = value => '`' + value.replaceAll('`', '``') + '`';
const literal = value => "'" + value.replaceAll('\\', '\\\\').replaceAll("'", "''") + "'";
function writeJson(file, value) { fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n'); }
function fileManifest(root) {
  return fs.readdirSync(root, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name)).flatMap(entry => {
    if (entry.isSymbolicLink()) throw Error('External links are not supported in a database snapshot');
    const file = path.join(root, entry.name);
    return entry.isDirectory() ? fileManifest(file).map(item => ({ ...item, path: entry.name + '/' + item.path }))
      : [{ path: entry.name, bytes: fs.statSync(file).size, sha256: hash(fs.readFileSync(file)) }];
  });
}
async function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer(); server.once('error', reject);
    server.listen(0, '127.0.0.1', () => { const port = server.address().port; server.close(error => error ? reject(error) : resolve(port)); });
  });
}
function startProcess(executable, args, cwd, logFile, env = {}) {
  const log = fs.openSync(logFile, 'a');
  let child;
  try { child = spawn(executable, args, { cwd, argv0: path.basename(executable), env: { ...process.env, ...env }, windowsHide: true, stdio: ['ignore', log, log] }); }
  finally { fs.closeSync(log); }
  const done = new Promise(resolve => { child.once('error', error => resolve({ error })); child.once('close', code => resolve({ code })); });
  return { child, done };
}
async function ready(process, predicate, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const exit = await Promise.race([process.done, delay(250, undefined, { ref: false })]);
    if (exit) throw Error(label + ' exited: ' + (exit.error?.message ?? exit.code));
    if (await predicate()) return;
    await delay(250);
  }
  throw Error(label + ' timed out');
}
class Database {
  constructor({ bundle, service, evidence }) {
    this.bin = path.join(bundle, 'resources/mysql/bin'); this.service = service; this.evidence = evidence;
    this.credentials = JSON.parse(fs.readFileSync(path.join(service, 'credentials.json'), 'utf8'));
    this.env = { ...process.env, MYSQL_PWD: this.credentials.root };
  }
  args() { return ['--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=' + this.port, '--user=root', '--default-character-set=utf8mb4', '--connect-timeout=3']; }
  sql(sql, database = 'ictwork') {
    return execFileSync(path.join(this.bin, 'mysql.exe'), [...this.args(), '--batch', '--skip-column-names', database],
      { input: sql, encoding: 'utf8', env: this.env, windowsHide: true, timeout: 120000, maxBuffer: 256 * 1024 * 1024 }).trimEnd();
  }
  rows(sql, database) { const out = this.sql(sql, database); return out ? out.split(/\r?\n/).map(row => row.split('\t')) : []; }
  async start() {
    this.port = await freePort();
    const mysqlPath = value => '"' + value.replaceAll('\\', '/').replaceAll('"', '\\"') + '"';
    // This file is owned by the copied profile. Never rewrite the source profile's options.
    const input = path.join(this.service, 'final-db-options.utf8'), output = path.join(this.service, 'final-db-mysql.ini');
    fs.writeFileSync(input, ['[mysqld]', 'basedir=' + mysqlPath(path.dirname(this.bin)), 'datadir=' + mysqlPath(path.join(this.service, 'mysql-data')),
      'bind-address=127.0.0.1', 'port=' + this.port, 'mysqlx=OFF', 'event-scheduler=OFF'].join('\n') + '\n');
    execFileSync(path.join(path.dirname(this.bin), '../app/windows-ansi.exe'), [input, output], { windowsHide: true, timeout: 15000 });
    fs.rmSync(input);
    this.process = startProcess(path.join(this.bin, 'mysqld.exe'), ['--defaults-file=final-db-mysql.ini', '--console'], this.service, path.join(this.evidence, 'mysql.log'));
    await ready(this.process, async () => { try { return this.sql('SELECT 1') === '1'; } catch { return false; } }, 60000, 'Copied MySQL');
  }
  async stop() {
    if (!this.process) return;
    if (this.process.child.exitCode === null) {
      execFileSync(path.join(this.bin, 'mysqladmin.exe'), [...this.args(), 'shutdown'], { env: this.env, windowsHide: true, timeout: 30000 });
    }
    const result = await Promise.race([this.process.done, delay(30000, null, { ref: false })]);
    if (!result || result.code !== 0) throw Error('Copied MySQL did not shut down cleanly');
  }
  async dump(database, target) {
    const child = spawn(path.join(this.bin, 'mysqldump.exe'), [...this.args().filter(arg => !arg.startsWith('--connect-timeout=')), '--single-transaction', '--routines', '--triggers', '--events', '--set-gtid-purged=OFF', '--hex-blob', '--no-tablespaces', '--skip-dump-date', database],
      { windowsHide: true, env: this.env, stdio: ['ignore', 'pipe', 'pipe'] });
    let error = ''; child.stderr.on('data', chunk => { error = (error + chunk).slice(-1500); });
    const done = new Promise((resolve, reject) => { child.once('error', reject); child.once('close', code => code === 0 ? resolve() : reject(Error('Database export failed: ' + error))); });
    const timer = setTimeout(() => child.kill(), 120000);
    try { await Promise.all([done, pipeline(child.stdout, fs.createWriteStream(target, { flags: 'wx' }))]); }
    finally { clearTimeout(timer); }
    return { file: path.basename(target), bytes: fs.statSync(target).size, sha256: hash(fs.readFileSync(target)) };
  }
  tables(database = 'ictwork') { return this.rows(`SELECT table_name FROM information_schema.tables WHERE table_schema=${literal(database)} AND table_type='BASE TABLE' ORDER BY table_name`).map(row => row[0]); }
  columns(table, database = 'ictwork') { return this.rows(`SELECT column_name FROM information_schema.columns WHERE table_schema=${literal(database)} AND table_name=${literal(table)} ORDER BY ordinal_position`).map(row => row[0]); }
  primaryKey(table, database = 'ictwork') { return this.rows(`SELECT column_name FROM information_schema.key_column_usage WHERE table_schema=${literal(database)} AND table_name=${literal(table)} AND constraint_name='PRIMARY' ORDER BY ordinal_position`).map(row => row[0]); }
  snapshot(database = 'ictwork') {
    return Object.fromEntries(this.tables(database).map(table => {
      // Hex encoding is lossless for text/binary, and distinguishes NULL from an empty value.
      const cols = this.columns(table, database);
      const rowHashes = this.rows('SELECT SHA2(CONCAT_WS(\'|\',' + cols.map(col => `IF(${ident(col)} IS NULL,'N',CONCAT('V',HEX(${ident(col)})))`).join(',') + `),256) FROM ${ident(database)}.${ident(table)} ORDER BY 1`).map(row => row[0]);
      return [table, { count: rowHashes.length, sha256: hash(rowHashes.join('\n')), rows: rowHashes }];
    }));
  }
  foreignKeyErrors(database = 'ictwork') {
    const references = this.rows(`SELECT constraint_name,table_name,column_name,referenced_table_name,referenced_column_name,ordinal_position FROM information_schema.key_column_usage WHERE table_schema=${literal(database)} AND referenced_table_name IS NOT NULL ORDER BY table_name,constraint_name,ordinal_position`);
    const groups = new Map();
    for (const row of references) { const key = row[1] + '.' + row[0]; if (!groups.has(key)) groups.set(key, []); groups.get(key).push(row); }
    return [...groups.values()].map(cols => {
      const [, table, , parent] = cols[0];
      const join = cols.map(col => `c.${ident(col[2])}=p.${ident(col[4])}`).join(' AND ');
      const nonnull = cols.map(col => `c.${ident(col[2])} IS NOT NULL`).join(' AND ');
      const count = Number(this.sql(`SELECT COUNT(*) FROM ${ident(database)}.${ident(table)} c LEFT JOIN ${ident(database)}.${ident(parent)} p ON ${join} WHERE ${nonnull} AND p.${ident(cols[0][4])} IS NULL`));
      return { constraint: cols[0][0], table, count };
    }).filter(item => item.count > 0);
  }
}
async function startBackend(db, { bundle, war, database, cwd, logFile, extraEnv = {} }) {
  const port = await freePort();
  const proc = startProcess(path.join(bundle, 'resources/java/bin/java.exe'), ['-jar', war, '--server.address=127.0.0.1', '--server.port=' + port, '--spring.profiles.active=demo'], cwd, logFile,
    { SPRING_DATASOURCE_URL: `jdbc:mysql://127.0.0.1:${db.port}/${database}?connectionTimeZone=%2B09:00&forceConnectionTimeZoneToSession=true&preserveInstants=false&characterEncoding=UTF-8`,
      SPRING_DATASOURCE_USERNAME: 'root', SPRING_DATASOURCE_PASSWORD: db.credentials.root, ICTWORK_INITIAL_PASSWORD: 'Local-Demo-Init!26', ICTWORK_AI_ENABLED: 'false', ICTWORK_SCHEDULER_ENABLED: 'false', ...extraEnv });
  const origin = 'http://127.0.0.1:' + port;
  try { await ready(proc, async () => fetch(origin + '/signin', { signal: AbortSignal.timeout(1500) }).then(response => response.ok).catch(() => false), 180000, 'Spring Boot ' + database); }
  catch (error) { proc.child.kill(); await proc.done; throw error; }
  return { ...proc, origin, async stop() { proc.child.kill(); await proc.done; } };
}
function sceneHashes(scenes) {
  if (!scenes.length) return [];
  const root=path.resolve(__dirname,'..'),war=path.join(root,'src/backend/build/libs/ictwork-0.0.1.war');
  const helper=path.join(__dirname,'DiagramSeedHash.java');
  const cache=path.join(root,'output/runtime/final-db-scenehash',hash(fs.readFileSync(war)).slice(0,16)+'-'+hash(fs.readFileSync(helper)).slice(0,8));
  const javaBin=path.join(root,'output/runtime/jdk-17.0.21.0.2+1/bin');
  const classpath=[cache,path.join(cache,'WEB-INF/classes'),path.join(cache,'WEB-INF/lib/*')].join(path.delimiter);
  if(!fs.existsSync(path.join(cache,'DiagramSeedHash.class'))){
    fs.mkdirSync(cache,{recursive:true});
    execFileSync(path.join(javaBin,'jar.exe'),['xf',war],{cwd:cache,windowsHide:true,timeout:60000});
    execFileSync(path.join(javaBin,'javac.exe'),['-encoding','UTF-8','-cp',classpath,'-d',cache,helper],{windowsHide:true,timeout:60000});
  }
  const result=execFileSync(path.join(javaBin,'java.exe'),['-cp',classpath,'DiagramSeedHash'],{input:scenes.map(scene=>Buffer.from(scene,'utf8').toString('base64')).join('\n')+'\n',encoding:'utf8',windowsHide:true,timeout:60000,maxBuffer:32*1024*1024}).trim().split(/\r?\n/);
  if(result.length!==scenes.length||!result.every(value=>/^[a-f0-9]{64}$/.test(value)))throw Error('Backend scene hash helper returned invalid results');
  return result;
}
module.exports = { Database, startBackend, fileManifest, writeJson, ident, literal, hash, sceneHashes };
