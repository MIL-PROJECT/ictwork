const { app, BrowserWindow } = require('electron');
const { spawn } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const net = require('node:net');
const path = require('node:path');
const { setTimeout: delay } = require('node:timers/promises');

const resources = process.resourcesPath;
const profile = path.join(app.getPath('appData'), 'OMNIS Desktop');
app.setPath('userData', profile);
const state = path.join(profile, 'service');
const data = path.join(state, 'mysql-data');
const mysqlBin = path.join(resources, 'mysql', 'bin');
const java = path.join(resources, 'java', 'bin', 'java.exe');
const war = path.join(resources, 'ictwork.war');
const demoHash = '$2b$12$yk3bmg6ARaWcA.OpWOIw2O3qMsEMtghYpzecJw58Td8xB2fG/RxXu';
const seedHash = '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm';
let window;
let backend;
let database;
let mysqlPort;
let credentials;
let stopping = false;
let stopped = false;
let startup = Promise.resolve();

function run(executable, args, logName, env = process.env) {
  const log = fs.openSync(path.join(state, logName), 'a');
  let child;
  try {
    child = spawn(executable, args, { cwd: state, env, windowsHide: true, stdio: ['ignore', log, log] });
  } finally {
    fs.closeSync(log);
  }
  const done = new Promise((resolve) => {
    child.once('error', (error) => resolve({ error }));
    child.once('close', (code) => resolve({ code }));
  });
  return { child, done };
}

async function check(process, description) {
  const { code, error } = await process.done;
  if (code !== 0) throw new Error(description + ': ' + (error?.message ?? code));
}

function client(tool, args, password) {
  return run(path.join(mysqlBin, tool + '.exe'), [
    '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=' + mysqlPort,
    '--user=root', '--connect-timeout=2', ...args,
  ], 'mysql-client.log', { ...process.env, MYSQL_PWD: password });
}

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close((error) => error ? reject(error) : resolve(port));
    });
  });
}

async function waitFor(process, ready, seconds, description) {
  const deadline = Date.now() + seconds * 1000;
  while (!stopping && Date.now() < deadline) {
    const exit = await Promise.race([process.done, delay(200, null)]);
    if (exit) throw new Error(description + ' stopped: ' + (exit.error?.message ?? exit.code));
    if (await ready()) return;
    await delay(300);
  }
  throw new Error(stopping ? 'Startup cancelled' : description + ' did not become ready');
}

function status(title, detail = '') {
  if (window?.isDestroyed()) return;
  const html = '<html lang="ko"><meta charset="utf-8"><body style="font:18px system-ui;padding:40px"><h1></h1><p></p>';
  const escape = (value) => value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
  void window.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(
    html.replace('<h1></h1>', '<h1>' + escape(title) + '</h1>').replace('<p></p>', '<p>' + escape(detail) + '</p>'),
  )).catch(() => {});
}

async function start() {
  fs.mkdirSync(state, { recursive: true });
  const config = path.join(state, 'credentials.json');
  if (!fs.existsSync(config)) {
    const secret = () => crypto.randomBytes(32).toString('hex');
    fs.writeFileSync(config, JSON.stringify({ root: secret(), app: secret() }), { flag: 'wx' });
  }
  credentials = JSON.parse(fs.readFileSync(config, 'utf8'));
  if (![credentials.root, credentials.app].every((value) => /^[a-f0-9]{64}$/.test(value))) {
    throw new Error('Invalid desktop database configuration');
  }

  status('OMNIS 시작 중', '시연 데이터베이스를 준비하고 있습니다.');
  const initialized = path.join(state, 'mysql-initialized');
  if (!fs.existsSync(initialized)) {
    await check(run(path.join(mysqlBin, 'mysqld.exe'), [
      '--no-defaults', '--initialize-insecure', '--datadir=' + data, '--console',
    ], 'mysql-init.log'), 'MySQL initialization');
    fs.writeFileSync(initialized, '');
  }
  if (stopping) return;

  mysqlPort = await freePort();
  const bootstrap = path.join(state, 'bootstrap.sql');
  const provisioned = path.join(state, 'mysql-provisioned');
  const firstStart = !fs.existsSync(provisioned);
  if (firstStart) {
    fs.writeFileSync(bootstrap, [
      'CREATE DATABASE IF NOT EXISTS ictwork CHARACTER SET utf8mb4;',
      "CREATE USER IF NOT EXISTS 'ictwork'@'127.0.0.1' IDENTIFIED BY '" + credentials.app + "';",
      "GRANT ALL ON ictwork.* TO 'ictwork'@'127.0.0.1';",
      "ALTER USER 'root'@'localhost' IDENTIFIED BY '" + credentials.root + "';",
    ].join('\n') + '\n');
  }
  database = run(path.join(mysqlBin, 'mysqld.exe'), [
    '--no-defaults', '--datadir=' + data, '--bind-address=127.0.0.1',
    '--port=' + mysqlPort, '--mysqlx=OFF', '--console',
    ...(firstStart ? ['--init-file=' + bootstrap] : []),
  ], 'mysql.log');
  await waitFor(database, async () => {
    const result = await client('mysql', ['--execute=SELECT 1'], credentials.root).done;
    return result.code === 0;
  }, 45, 'MySQL');
  if (firstStart) {
    fs.writeFileSync(provisioned, '');
    fs.rmSync(bootstrap, { force: true });
  }
  if (stopping) return;

  status('OMNIS 시작 중', 'Java 백엔드와 업무 화면을 준비하고 있습니다.');
  const webPort = await freePort();
  const origin = 'http://127.0.0.1:' + webPort;
  backend = run(java, [
    '-jar', war, '--server.address=127.0.0.1', '--server.port=' + webPort, '--spring.profiles.active=demo',
  ], 'spring.log', {
    ...process.env,
    SPRING_DATASOURCE_URL: 'jdbc:mysql://127.0.0.1:' + mysqlPort + '/ictwork?connectionTimeZone=%2B09:00&forceConnectionTimeZoneToSession=true&preserveInstants=false&characterEncoding=UTF-8',
    SPRING_DATASOURCE_USERNAME: 'ictwork',
    SPRING_DATASOURCE_PASSWORD: credentials.app,
    ICTWORK_INITIAL_PASSWORD: 'Local-Demo-Init!26',
  });
  await waitFor(backend, async () => {
    try {
      return (await fetch(origin + '/signin', { signal: AbortSignal.timeout(1000) })).ok;
    } catch {
      return false;
    }
  }, 120, 'Spring Boot');
  if (stopping) return;

  // Provision only the unchanged synthetic account from the demo seed.
  await check(client('mysql', ['--database=ictwork', '--execute=' +
    "UPDATE account SET password_hash='" + demoHash + "', password_change_required=FALSE " +
    "WHERE login_id='bn1-co1-admin' AND password_hash='" + seedHash + "'"], credentials.root),
  'Demo account setup');

  window.webContents.on('will-navigate', (event, target) => {
    if (new URL(target).origin !== origin) event.preventDefault();
  });
  await window.loadURL(origin + '/signin');
  for (const [service, name] of [[backend, 'Java'], [database, 'MySQL']]) {
    void service.done.then(() => {
      if (!stopping) status('OMNIS 서비스가 종료됐습니다', name + ' 로그: ' + state);
    });
  }
}

async function shutdown() {
  if (backend) {
    backend.child.kill();
    await Promise.race([backend.done, delay(3000)]);
  }
  if (database && database.child.exitCode === null) {
    const admin = client('mysqladmin', ['shutdown'], credentials.root);
    const result = await Promise.race([admin.done, delay(10000, null)]);
    if (!result || result.code !== 0) {
      admin.child.kill();
      database.child.kill();
    }
    await Promise.race([database.done, delay(3000)]);
  }
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (window?.isMinimized()) window.restore();
    window?.focus();
  });
  app.on('window-all-closed', () => app.quit());
  app.on('before-quit', (event) => {
    if (stopped) return;
    event.preventDefault();
    if (stopping) return;
    stopping = true;
    void startup.then(shutdown).finally(() => {
      stopped = true;
      app.quit();
    });
  });
  void app.whenReady().then(() => {
    window = new BrowserWindow({
      width: 1440, height: 950, title: 'OMNIS', icon: path.join(__dirname, 'omnis.ico'),
      webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false },
    });
    window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
    startup = start().catch(async (error) => {
      if (!stopping) status('OMNIS 시작 실패', error.message + ' · 로그: ' + state);
      await shutdown();
    });
  });
}
