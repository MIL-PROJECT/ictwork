// Run current React source in Electron, using bundled services and a separate DB.
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const { execFileSync } = require('node:child_process');
const { pathToFileURL } = require('node:url');
const { _electron } = require('playwright');

const root = path.resolve(__dirname, '..');
const frontend = path.join(root, 'src/frontend');
const resources = path.resolve(process.env.OMNIS_DEV_RESOURCES || path.join(root, 'output/OMNIS-Final-Database-Windows-x64/resources'));
const directory = path.join(root, 'output/dev');
const builtWar = path.join(root, 'src/backend/build/libs/ictwork-0.0.1.war');
const profile = path.join(directory, 'profile');
const electronExe = process.env.OMNIS_ELECTRON_EXE || path.join(root, '../military_project/node_modules/electron/dist/electron.exe');
let vite;
let electron;
let closing = false;

async function freePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const port = server.address().port;
  await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  return port;
}

async function close() {
  if (closing) return;
  closing = true;
  try { if (electron) await electron.close(); }
  finally { if (vite) await vite.close(); }
}

async function start() {
  for (const file of [electronExe, path.join(resources, 'java/bin/java.exe'), path.join(resources, 'mysql/bin/mysqld.exe'), path.join(resources, 'ictwork.war'), path.join(resources, 'app/windows-ansi.exe')]) {
    if (!fs.existsSync(file)) throw Error('개발 실행에 필요한 파일이 없습니다: ' + file);
  }
  const viteEntry = require.resolve('vite', { paths: [frontend] });
  fs.mkdirSync(directory, { recursive: true });
  execFileSync(process.execPath, [path.join(frontend, 'scripts/copy-excalidraw-assets.mjs')], { stdio: 'inherit', windowsHide: true });
  const backendPort = await freePort();
  const { createServer } = await import(pathToFileURL(viteEntry).href);
  vite = await createServer({
    root: frontend,
    configFile: path.join(frontend, 'vite.config.ts'),
    server: {
      host: '127.0.0.1', port: 5173, strictPort: true,
      proxy: { '/api': 'http://127.0.0.1:' + backendPort },
    },
  });
  await vite.listen();
  const ui = 'http://127.0.0.1:5173';
  // Run a snapshot so rebuilding the source WAR never modifies an open Java archive.
  const runtimeWar = path.join(directory, 'source-runtime.war');
  const selectedWar = process.env.OMNIS_DEV_WAR || (fs.existsSync(builtWar) ? builtWar : path.join(resources, 'ictwork.war'));
  if (path.resolve(selectedWar) !== path.resolve(runtimeWar)) fs.copyFileSync(selectedWar, runtimeWar);
  const env = {
    ...process.env,
    OMNIS_DEV_RESOURCES: resources,
    OMNIS_DEV_URL: ui,
    OMNIS_DEV_BACKEND_PORT: String(backendPort),
    OMNIS_DEV_WAR: runtimeWar,
    ICTWORK_SCHEDULER_ENABLED: 'false',
    ICTWORK_AI_ENABLED: process.env.ICTWORK_AI_ENABLED || 'true',
  };
  delete env.ELECTRON_RUN_AS_NODE;
  electron = await _electron.launch({
    executablePath: electronExe,
    args: [__dirname, '--omnis-dev', '--user-data-dir=' + profile],
    env, timeout: 60000,
  });
  electron.on('close', () => {
    electron = undefined;
    void close().catch(error => { console.error(error); process.exitCode = 1; });
  });
  let ready = false;
  for (let attempt = 0; attempt < 480 && !closing; attempt++) {
    try {
      ready = (await fetch('http://127.0.0.1:' + backendPort + '/signin', { signal: AbortSignal.timeout(700) })).ok;
      if (ready) break;
    } catch { /* Java/MySQL are still starting. */ }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  if (!ready) throw Error('개발 서버가 시작되지 않았습니다. output/dev/profile/service/spring.log를 확인하세요.');
  const session = { pid: process.pid, url: ui, backend: 'http://127.0.0.1:' + backendPort, profile, startedAt: new Date().toISOString() };
  fs.writeFileSync(path.join(directory, 'session.json'), JSON.stringify(session, null, 2));
  console.log('OMNIS 개발 모드 준비 완료: ' + ui);
  console.log('React/CSS 저장 시 자동 반영 · 종료: OMNIS 창 닫기 또는 Ctrl+C');
  console.log('개발 DB/로그: ' + profile);
}

for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { void close().catch(error => { console.error(error); process.exitCode = 1; }); });
start().catch(async error => {
  if (closing) return;
  console.error(error);
  process.exitCode = 1;
  await close();
});
