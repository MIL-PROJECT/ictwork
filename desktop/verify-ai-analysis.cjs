// Actual HTTP/API verification against start-source-validation's disposable clone.
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const { request } = require('playwright');
const latest = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../output/source-validation/latest.json'), 'utf8'));
const fixture = JSON.parse(fs.readFileSync(latest.fixture, 'utf8'));
const contexts = [], report = { success: false, checks: [] };
async function ctx() { const api = await request.newContext({ baseURL: fixture.origin }); contexts.push(api); return api; }
async function csrf(api) { await api.get('/api/me'); const token = (await api.storageState()).cookies.find(c => c.name === 'XSRF-TOKEN'); assert.ok(token); return { 'X-XSRF-TOKEN': decodeURIComponent(token.value) }; }
async function expect(response, status) { assert.equal(response.status(), status, (await response.text()).slice(0, 250)); return response.json(); }
async function login(api, loginId) { return expect(await api.post('/api/auth/login', { headers: await csrf(api), form: { loginId, password: fixture.adminPassword } }), 200); }
async function analyze(api, data) { return expect(await api.post('/api/ai/analysis', { headers: await csrf(api), data }), 200); }
(async () => {
  const anon = await ctx(), admin = await ctx(), normal = await ctx();
  await expect(await anon.post('/api/ai/analysis', { headers: await csrf(anon), data: { feature: 'SUMMARY' } }), 401);
  await login(admin, fixture.adminLogin);
  await expect(await admin.post('/api/ai/analysis', { data: { feature: 'SUMMARY' } }), 403);
  for (const data of [{ feature: 'UNSUPPORTED' }, { feature: 'SIMILAR', title: 'x' }, { feature: 'TRIAGE', title: 'x'.repeat(201) }]) {
    await expect(await admin.post('/api/ai/analysis', { headers: await csrf(admin), data }), 400);
  }
  report.checks.push('anonymous/CSRF/invalid feature and length protections');
  const accounts = await expect(await admin.get('/api/admin/system/accounts?search=source-'), 200);
  const member = accounts.items.find(row => row.loginId.startsWith('source-') && !row.roles.some(role => role.roleCode === 'SYS_ADMIN'));
  assert.ok(member, 'Run verify-source-api.cjs first to create an approved ordinary fixture');
  await login(normal, member.loginId);
  const open = new Set(['RECEIVED', 'IN_APPROVAL', 'APPROVED', 'PLANNED']);
  let smallScope, largeScope;
  for (const [name, api] of [['admin', admin], ['ordinary', normal]]) {
    const systems = await expect(await api.get('/api/operations'), 200);
    const work = await expect(await api.get('/api/work/items'), 200);
    const visibleIds = new Set(work.map(row => row.id));
    if (name === 'admin') largeScope = visibleIds; else smallScope = visibleIds;
    const summary = await analyze(api, { feature: 'SUMMARY' });
    const metrics = Object.fromEntries(summary.metrics.map(row => [row.label, row.value]));
    assert.equal(metrics['전체 체계'], systems.items.length);
    assert.equal(metrics['미완료 업무'], work.filter(row => open.has(row.status)).length);
    assert.equal(metrics['긴급 미완료'], work.filter(row => open.has(row.status) && row.priority === 'URGENT').length);
    const priority = await analyze(api, { feature: 'PRIORITY' });
    assert.ok(priority.items.length <= 5);
    for (const row of priority.items) { assert.ok(visibleIds.has(row.id)); assert.ok(open.has(row.status)); assert.ok(row.href.startsWith('/work/items/')); }
    const similar = await analyze(api, { feature: 'SIMILAR', title: '무전기 통신 장비' });
    assert.ok(similar.items.length <= 5);
    for (const row of similar.items) assert.ok(visibleIds.has(row.id));
    report.checks.push(name + ': actual scoped counts, open priority records and similar records reference existing visible IDs');
  }
  assert.ok(largeScope.size > smallScope.size, 'Fixture proves unequal permission scopes');
  const secretText = 'PRIVATE-SYMPTOMS-SHOULD-NOT-ENTER-AUDIT';
  const triage = await analyze(normal, { feature: 'TRIAGE', title: '위성 통신 불통', description: secretText, serviceImpact: '서비스 중단' });
  assert.equal(triage.advice.category, '무선/위성'); assert.equal(triage.advice.priority, 'URGENT'); assert.equal(triage.provider, 'LOCAL');
  const none = await analyze(normal, { feature: 'SIMILAR', title: 'ZZUNMATCHEDXYZ987654' }); assert.equal(none.items.length, 0);
  const audit = await expect(await admin.get('/api/admin/system/audit?search=AI_ANALYSIS'), 200);
  assert.ok(audit.items.length >= 4); assert.ok(!JSON.stringify(audit).includes(secretText));
  report.checks.push('local triage, no invented matches and audit metadata without symptoms');
  report.success = true;
})().catch(error => { report.error = error.message; process.exitCode = 1; console.error(error.message); }).finally(async () => {
  fs.writeFileSync(path.join(fixture.evidence, 'ai-analysis-api.json'), JSON.stringify(report, null, 2));
  await Promise.all(contexts.map(api => api.dispose()));
  console.log(JSON.stringify({ success: report.success, checks: report.checks.length, evidence: fixture.evidence }));
});
