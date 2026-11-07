const { _electron } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const [installed, evidence] = process.argv.slice(2);
if (!installed || !evidence) throw Error('Usage: node setup-smoke.cjs <installed-directory> <evidence-directory>');
const profile = path.resolve(evidence, process.env.OMNIS_SETUP_TEST_PROFILE || 'fresh-user-profile');
const result = { installed, profile, runs: [], unexpectedNetwork: [] };
const eventTitle = 'Setup 설치·재시작 검증 ' + Date.now();
let eventId;

async function csrfHeaders(context, origin) {
  const token = (await context.cookies(origin)).find(cookie => cookie.name === 'XSRF-TOKEN');
  assert.ok(token, 'CSRF token must exist');
  return { 'X-XSRF-TOKEN': decodeURIComponent(token.value) };
}

(async () => {
  assert.ok(!fs.existsSync(profile), 'Must initialize a genuinely new isolated profile');
  for (let run = 1; run <= 2; run++) {
    const start = Date.now();
    const env = { ...process.env, PATH: path.join(process.env.SystemRoot, 'System32') };
    delete env.JAVA_HOME;
    delete env.JDK_HOME;
    delete env.ELECTRON_RUN_AS_NODE;
    const electron = await _electron.launch({
      executablePath: path.join(installed, 'OMNIS.exe'),
      args: ['--user-data-dir=' + profile], env,
      timeout: 60000,
    });
    const page = await electron.firstWindow();
    let shutdownAt;
    let serviceOrigin;
    try {
      await page.context().route('**/*', route => {
        const url = new URL(route.request().url());
        if (['http:', 'https:'].includes(url.protocol) && !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)) {
          result.unexpectedNetwork.push(url.origin);
          return route.abort();
        }
        return route.continue();
      });
      const actual = await electron.evaluate(({ app, BrowserWindow }) => {
        const prefs = BrowserWindow.getAllWindows()[0].webContents.getLastWebPreferences();
        return { profile: app.getPath('userData'), executable: app.getPath('exe'), sandbox: prefs.sandbox, contextIsolation: prefs.contextIsolation, nodeIntegration: prefs.nodeIntegration };
      });
      assert.equal(path.resolve(actual.profile).toLowerCase(), profile.toLowerCase());
      assert.equal(path.resolve(actual.executable).toLowerCase(), path.join(installed, 'OMNIS.exe').toLowerCase());
      assert.ok(actual.sandbox && actual.contextIsolation && !actual.nodeIntegration);
      await Promise.race([
        page.locator('[name=loginId]').waitFor({ timeout: 180000 }),
        page.locator('h1').filter({ hasText: /OMNIS 시작 실패|OMNIS 서비스가 종료됐습니다/ }).waitFor({ timeout: 180000 }).then(async () => { throw Error(await page.locator('body').innerText()); }),
      ]);
      const startSeconds = (Date.now() - start) / 1000;
      const origin = new URL(page.url()).origin;
      serviceOrigin = origin;
      assert.match(origin, /^http:\/\/127\.0\.0\.1:\d+$/);
      if (run === 1) await page.screenshot({ path: path.join(evidence, 'installed-login.png') });
      await page.locator('[name=loginId]').fill('bn1-co1-admin');
      await page.locator('[name=password]').fill('Local-Demo-Init!26');
      const [login] = await Promise.all([
        page.waitForResponse(response => response.url().endsWith('/api/auth/login') && response.request().method() === 'POST'),
        page.locator('[name=password]').press('Enter'),
      ]);
      assert.equal(login.status(), 200);
      await page.locator('aside').first().getByRole('link', { name: '관리', exact: true }).waitFor();
      await page.getByText('현황 전체', { exact: true }).waitFor({ timeout: 30000 });
      const metrics = await page.locator('.board-metric-value').allTextContents();
      assert.equal(metrics.length, 4);
      assert.ok(metrics.every(value => !value.includes('—')), 'Dashboard metrics must load from the bundled backend');
      const api = page.request;
      const me = await api.get(origin + '/api/me');
      assert.equal(me.status(), 200);
      const headers = await csrfHeaders(page.context(), origin);
      if (run === 1) {
        const create = await api.post(origin + '/api/calendar/events', { headers, data: {
          title: eventTitle, startDate: '2026-11-07', endDate: '2026-11-07', color: 'Primary', important: false, weekdaysOnly: false,
        } });
        assert.equal(create.status(), 200, await create.text());
        eventId = (await create.json()).id;
        assert.ok(eventId);
      }
      const events = await api.get(origin + '/api/calendar/events');
      assert.equal(events.status(), 200);
      assert.ok((await events.json()).some(event => event.id === eventId && event.title === eventTitle));
      await page.screenshot({ path: path.join(evidence, 'installed-dashboard-run-' + run + '.png') });
      await page.goto(origin + '/approvals/request');
      await page.getByRole('navigation', { name: '업무 요청 작성 단계' }).waitFor();
      await page.goto(origin + '/department/diagrams');
      await page.locator('main h1').waitFor();
      await page.screenshot({ path: path.join(evidence, 'installed-diagrams-run-' + run + '.png') });
      await page.goto(origin + '/department/docs');
      await page.locator('main h1').waitFor();
      if (run === 1) await require('./verify-navigation.cjs')(page, origin, installed, profile, evidence);
      if (run === 2) {
        const workflow = JSON.parse(fs.readFileSync(path.join(evidence,'workflow-result.json'),'utf8'));
        const completed = await api.get(origin+'/api/work/items/'+workflow.completedItemId+'?from=department');
        assert.equal(completed.status(),200);
        assert.equal((await completed.json()).status,'DONE');
        const rejected = await api.get(origin+'/api/work/items/'+workflow.rejectedItemId+'?from=department');
        assert.equal(rejected.status(),200);
        assert.equal((await rejected.json()).status,'REJECTED');
        const registered = await api.get(origin+'/api/work/requests/'+workflow.registeredRequestId+'?from=mine');
        assert.equal(registered.status(),200);
        assert.equal((await registered.json()).kind,'REGISTER');
        const registration=JSON.parse(fs.readFileSync(path.join(evidence,'registration-result.json'),'utf8'));
        for(const id of [registration.uiRequestId,registration.pendingRequestId]) {
          const response=await api.get(origin+'/api/work/requests/'+id+'?from=mine');
          assert.equal(response.status(),200);
          const data=await response.json();
          assert.equal(data.kind,'REGISTER');
          assert.equal(data.phase,'SENT');
          assert.equal(data.items.length,1);
        }
        console.log('PASS request, registration, completion and rejection persisted across restart');
      }
      result.runs.push({ run, startSeconds, origin, loginStatus: login.status(), dashboardMetrics: metrics, eventId, persistence: true, rendererIsolation: actual.sandbox && actual.contextIsolation && !actual.nodeIntegration });
      console.log('PASS installed launch ' + run + ', login, synthetic event persistence, request/diagrams/docs, ' + startSeconds.toFixed(1) + 's startup');
      shutdownAt = Date.now();
    } catch (error) {
      console.error(await page.locator('body').innerText().catch(() => 'No page text'));
      await page.screenshot({ path: path.join(evidence, 'failure.png') }).catch(() => {});
      throw error;
    } finally {
      const process = electron.process();
      const exited = new Promise(resolve => process.once('exit', resolve));
      await page.close().catch(() => {});
      await Promise.race([exited, new Promise((_, reject) => setTimeout(() => reject(Error('App did not stop its services')), 25000).unref())]);
      if (shutdownAt) result.runs.at(-1).shutdownSeconds = (Date.now() - shutdownAt) / 1000;
    }
    const mysqlLog = fs.readFileSync(path.join(profile, 'service/mysql.log'), 'utf8');
    assert.ok((mysqlLog.match(/Shutdown complete/g) || []).length >= run, 'Every launch must gracefully stop MySQL');
    const backendStillRunning = await fetch(serviceOrigin + '/signin', { signal: AbortSignal.timeout(1500) }).then(() => true).catch(() => false);
    assert.equal(backendStillRunning, false, 'Java backend must stop when its window closes');
    console.log('PASS normal window close and MySQL graceful shutdown ' + run);
  }
  assert.equal(result.unexpectedNetwork.length, 0, 'Core flow must not request external network');
  result.success = true;
  fs.writeFileSync(path.join(evidence, 'installed-smoke-result.json'), JSON.stringify(result, null, 2));
})().catch(error => {
  result.success = false;
  result.error = String(error.stack || error);
  fs.writeFileSync(path.join(evidence, 'installed-smoke-result.json'), JSON.stringify(result, null, 2));
  console.error(error);
  process.exitCode = 1;
});
