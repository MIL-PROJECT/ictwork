const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict'), crypto = require('node:crypto');
const { request } = require('playwright');
const { Database } = require('./final-db-tools.cjs');
const root = path.resolve(__dirname, '..');
const latest = JSON.parse(fs.readFileSync(path.join(root, 'output/source-validation/latest.json'), 'utf8'));
const fixture = JSON.parse(fs.readFileSync(latest.fixture, 'utf8'));
const db = new Database({ bundle: path.join(root, 'output/OMNIS-Final-Database-Windows-x64'), service: path.join(fixture.profile, 'service'), evidence: fixture.evidence });
db.port = fixture.mysqlPort;
const checks = [];
const contexts = [];
const report = { success: false, checks };
async function context() { const api = await request.newContext({ baseURL: fixture.origin, timeout: 30000 }); contexts.push(api); return api; }
async function csrf(api) {
  await api.get('/api/me');
  const cookie = (await api.storageState()).cookies.find(c => c.name === 'XSRF-TOKEN');
  assert.ok(cookie, 'CSRF token supplied');
  return { 'X-XSRF-TOKEN': decodeURIComponent(cookie.value) };
}
async function post(api, url, data, method = 'post') { return api[method](url, { headers: await csrf(api), data }); }
async function expect(response, status, label) { assert.equal(response.status(), status, label + ': ' + (await response.text()).slice(0, 220)); return status === 204 ? null : response.json(); }
async function login(api, loginId, password) { return expect(await api.post('/api/auth/login', { headers: await csrf(api), form: { loginId, password } }), 200, 'Login'); }
const token = crypto.randomBytes(4).toString('hex');
let service = Number.parseInt(token, 16) % 90000000 + 10000000;
const input = suffix => ({ loginId: 'source-' + token + '-' + suffix, password: fixture.adminPassword, serviceNo: '26-' + service++, displayName: '소스검증 ' + suffix, rankCode: 'PVT', unitName: '검증용 소속', contact: '010-1234-5678', reason: '격리 소스 검증', role: 'SYS_ADMIN' });
(async () => {
  const anon = await context(), admin = await context(), regular = await context();
  await expect(await anon.get('/api/admin/system/summary'), 401, 'Anonymous system API');
  const me = await login(admin, fixture.adminLogin, fixture.adminPassword);
  assert.ok(me.features.includes('ADMIN_SYSTEM'));
  const ordinary = await login(regular, 'bn1-co1-admin', fixture.adminPassword);
  assert.ok(!ordinary.features.includes('ADMIN_SYSTEM'));
  for (const endpoint of ['summary', 'accounts', 'registrations', 'security', 'audit', 'ai']) await expect(await regular.get('/api/admin/system/' + endpoint), 403, 'Non-super access ' + endpoint);
  checks.push('anonymous and department users cannot access any system administration endpoint');

  const candidate = input('approved');
  await expect(await anon.post('/api/registrations', { data: candidate }), 403, 'Missing CSRF');
  const submitted = await expect(await post(anon, '/api/registrations', candidate), 201, 'Registration');
  await expect(await post(anon, '/api/registrations', candidate), 409, 'Duplicate registration');
  assert.equal(db.rows("SELECT COUNT(*) FROM account WHERE login_id='" + candidate.loginId + "'")[0][0], '0');
  const departments = await expect(await admin.get('/api/departments'), 200, 'Departments');
  const withoutOperations = db.rows("SELECT d.id FROM department d WHERE d.active=TRUE AND d.type='SECTION' AND NOT EXISTS(SELECT 1 FROM operation_system s WHERE s.department_id=d.id AND s.deleted_at IS NULL) ORDER BY d.id LIMIT 1")[0];
  const departmentId = Number(withoutOperations?.[0] || departments[0].id);
  const review = { expectedVersion: 0, approve: true, departmentId, role: 'USER', reason: '가입 승인 검증', adminPassword: fixture.adminPassword };
  await expect(await post(regular, `/api/admin/system/registrations/${submitted.id}/review`, review), 403, 'Department admin approval');
  await expect(await post(admin, `/api/admin/system/registrations/${submitted.id}/review`, { ...review, adminPassword: 'incorrect-password' }), 403, 'Admin reauthentication');
  const raced = await Promise.all([post(admin, `/api/admin/system/registrations/${submitted.id}/review`, review), post(admin, `/api/admin/system/registrations/${submitted.id}/review`, review)]);
  assert.deepEqual(raced.map(r => r.status()).sort(), [204, 409], 'Exactly one approval commits');
  assert.equal(db.rows("SELECT COUNT(*) FROM account WHERE login_id='" + candidate.loginId + "'")[0][0], '1');
  assert.equal(db.rows('SELECT password_hash IS NULL FROM signup_request WHERE id=' + submitted.id)[0][0], '1');
  const user = await context();
  const created = await login(user, candidate.loginId, candidate.password);
  assert.ok(!created.features.includes('ADMIN_SYSTEM'), 'Client-injected SYS_ADMIN never self grants');
  checks.push('CSRF, duplicates, no login before approval, reauthentication, concurrent approval and no self-assigned privilege');

  const rejected = input('rejected');
  const rejectId = (await expect(await post(anon, '/api/registrations', rejected), 201, 'Second registration')).id;
  await expect(await post(admin, `/api/admin/system/registrations/${rejectId}/review`, { ...review, approve: false, reason: '소속 확인 필요' }), 204, 'Reject');
  assert.equal(db.rows("SELECT COUNT(*) FROM account WHERE login_id='" + rejected.loginId + "'")[0][0], '0');
  checks.push('rejection does not create an account and clears stored request password hash');

  async function accountRow(loginId) { return (await expect(await admin.get('/api/admin/system/accounts?search=' + encodeURIComponent(loginId)), 200, 'Account lookup')).items.find(row => row.loginId === loginId); }
  async function mutate(action, extra = {}, version) {
    const row = await accountRow(candidate.loginId);
    return post(admin, `/api/admin/system/accounts/${row.id}`, { expectedVersion: version ?? row.version, action, reason: '계정 제어 검증', adminPassword: fixture.adminPassword, ...extra });
  }
  const initial = await accountRow(candidate.loginId);
  await expect(await mutate('LOCK'), 204, 'Manual lock');
  await expect(await user.get('/api/me'), 401, 'Existing session revoked');
  await expect(await mutate('UNLOCK', {}, initial.version), 409, 'Optimistic conflict');
  await expect(await mutate('UNLOCK'), 204, 'Unlock');
  await login(user, candidate.loginId, candidate.password);
  await expect(await mutate('ROLE', { role: 'SYS_ADMIN' }), 204, 'Grant highest role');
  const promoted = await expect(await user.get('/api/me'), 200, 'Updated roles');
  assert.ok(promoted.features.includes('ADMIN_SYSTEM'));
  await expect(await mutate('ROLE', { role: 'USER' }), 204, 'Revoke highest role');
  await expect(await user.get('/api/admin/system/summary'), 403, 'Revocation immediately enforced');
  const adminRow = await accountRow(fixture.adminLogin);
  await expect(await post(admin, `/api/admin/system/accounts/${adminRow.id}`, { action: 'DISABLE', expectedVersion: adminRow.version, reason: '자기 계정 보호', adminPassword: fixture.adminPassword }), 409, 'Self protection');
  checks.push('locking revokes sessions, versions prevent lost updates, role grant/revoke is enforced server-side, own administrator protected');

  const policy = await expect(await admin.get('/api/admin/system/security'), 200, 'Policy');
  await expect(await post(admin, '/api/admin/system/security', { expectedVersion: policy.version, failedLimit: 3, lockMinutes: 1, reason: '잠금 정책 검증', adminPassword: fixture.adminPassword }, 'put'), 200, 'Save security policy');
  for (let attempt = 0; attempt < 3; attempt++) await expect(await user.post('/api/auth/login', { headers: await csrf(user), form: { loginId: candidate.loginId, password: 'incorrect-password' } }), 401, 'Failed login');
  await expect(await user.post('/api/auth/login', { headers: await csrf(user), form: { loginId: candidate.loginId, password: candidate.password } }), 401, 'Automatic lock');
  await expect(await mutate('CLEAR_AUTO_LOCK'), 204, 'Clear automatic lock');
  await login(user, candidate.loginId, candidate.password);
  checks.push('configured failed-login threshold and automatic lock/unlock work through actual authentication');

  const config = await expect(await admin.get('/api/admin/system/ai'), 200, 'AI settings');
  const fakeKey = 'sk-synthetic-fixture-not-a-real-key';
  await expect(await post(admin, '/api/admin/system/ai', { expectedVersion: config.version, settings: { ...config.settings, provider: 'LOCAL', enabled: true, externalAiEnabled: false, externalAiConsentAt: null }, credentialProvider: 'OPENAI', apiKey: fakeKey, reason: '키 암호화와 로컬 모드 검증', adminPassword: fixture.adminPassword }, 'put'), 200, 'Save AI configuration');
  const safeConfig = await expect(await admin.get('/api/admin/system/ai'), 200, 'Masked credentials');
  assert.equal(safeConfig.credentials.OPENAI, true);
  assert.ok(!JSON.stringify(safeConfig).includes(fakeKey));
  assert.ok(!db.sql("SELECT value_json FROM system_setting WHERE setting_key='ai-secret-OPENAI'").includes(fakeKey));
  const cleared = await expect(await post(admin, '/api/admin/system/ai', { expectedVersion: safeConfig.version, settings: safeConfig.settings, credentialProvider: 'OPENAI', clearCredential: true, reason: '검증용 가상 키 정리', adminPassword: fixture.adminPassword }, 'put'), 200, 'Clear synthetic credential');
  assert.equal(cleared.credentials.OPENAI, false);
  const status = await expect(await user.get('/api/ai/status'), 200, 'Local AI readiness');
  assert.equal(status.provider, 'LOCAL'); assert.equal(status.reachable, true);
  async function ask(api, question) {
    const conversation = await expect(await post(api, '/api/ai/conversations', {}), 201, 'Create conversation');
    const response = await post(api, `/api/ai/conversations/${conversation.id}/ask`, { question });
    assert.equal(response.status(), 200, 'SSE response');
    const text = await response.text();
    assert.match(text, /event:done|event: done/); assert.ok(!/event:\s*error/.test(text), text.slice(-500));
    return { conversation, text, detail: await expect(await api.get('/api/ai/conversations/' + conversation.id), 200, 'Persisted AI response') };
  }
  const actual = await expect(await admin.get('/api/operations'), 200, 'Actual operations');
  const answer = await ask(admin, '전체 체계 요약 알려줘');
  const content = answer.detail.messages.filter(m => m.role === 'ASSISTANT').at(-1)?.content || '';
  assert.ok(content.includes('전체 체계') && content.includes(String(actual.items.length)), content);
  assert.ok(!content.includes('체계 자료에서 찾지 못했습니다'), 'Local tool result is usable');
  const ownOperations = await expect(await user.get('/api/operations'), 200, 'User operation scope');
  const scoped = await ask(user, '전체 체계 요약 알려줘');
  const scopedText = scoped.detail.messages.filter(m => m.role === 'ASSISTANT').at(-1)?.content || '';
  assert.ok(scopedText.includes('전체 체계: ' + ownOperations.items.length), scopedText);
  await expect(await user.get('/api/ai/conversations/' + answer.conversation.id), 404, 'Private conversation');
  checks.push('encrypted API credentials, masked configuration, no-model local SSE response, real permission-scoped counts and private history');

  const audit = await expect(await admin.get('/api/admin/system/audit'), 200, 'Audit records');
  assert.ok(audit.items.some(row => row.action.startsWith('ACCOUNT_')));
  assert.ok(!JSON.stringify(audit).includes(fixture.adminPassword) && !JSON.stringify(audit).includes(fakeKey));
  for (const endpoint of ['/api/admin/equipment', '/api/admin/work-types/departments', '/api/admin/locations']) {
    await expect(await admin.get(endpoint), 200, 'Highest administrator management ' + endpoint);
  }
  checks.push('highest administrator management entry points and audit records checked without recording secrets');
  report.success = true;
})().catch(error => { report.error = error.message; console.error(error.message); process.exitCode = 1; }).finally(async () => {
  fs.writeFileSync(path.join(fixture.evidence, 'source-api-result.json'), JSON.stringify(report, null, 2));
  await Promise.all(contexts.map(api => api.dispose()));
  console.log(JSON.stringify({ success: report.success, checks: checks.length, report: path.join(fixture.evidence, 'source-api-result.json') }));
});
