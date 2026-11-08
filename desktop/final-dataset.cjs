// Optional installer dataset. It can only initialize an entirely empty database.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const hash = data => crypto.createHash('sha256').update(data).digest('hex');
async function installFinalDataset({ directory, state, query, restore, record }) {
  const manifestFile = path.join(directory, 'manifest.json');
  if (!fs.existsSync(manifestFile)) return { installed: false, reason: 'not-bundled' };
  const manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
  if (manifest.format !== 1 || !/^[a-f0-9]{64}$/.test(manifest.sql?.sha256 || '') || !manifest.tables || typeof manifest.tables !== 'object') {
    throw Error('최종 DB 배포 명세가 올바르지 않습니다.');
  }
  const marker = path.join(state, 'final-dataset-installed.json');
  const pending = path.join(state, 'final-dataset-importing.json');
  // A successful import never runs again, including after the user edits data.
  if (fs.existsSync(marker)) return { installed: false, reason: 'already-installed' };
  if (fs.existsSync(pending)) throw Error('이 프로필의 최종 DB 가져오기가 중단됐습니다. 원본을 보존하고 새 프로필에서 다시 실행하세요.');
  const tables = await query("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='ictwork'");
  if (tables[0]?.[0] !== '0') return { installed: false, reason: 'existing-database-preserved' };
  const sql = path.join(directory, 'final-db.sql');
  if (hash(fs.readFileSync(sql)) !== manifest.sql.sha256) throw Error('최종 DB SQL 해시가 일치하지 않습니다.');
  const qa = path.join(directory, 'qa-config.yml');
  if (manifest.qaConfig && hash(fs.readFileSync(qa)) !== manifest.qaConfig.sha256) throw Error('QA 설정 해시가 일치하지 않습니다.');
  for (const [table, info] of Object.entries(manifest.tables)) {
    if (!/^[A-Za-z0-9_]+$/.test(table) || !Number.isSafeInteger(info.count) || info.count < 0) throw Error('최종 DB 테이블 명세가 올바르지 않습니다.');
  }
  await record(pending, { sqlSha256: manifest.sql.sha256, phase: 'importing' });
  await restore(sql);
  for (const [table, info] of Object.entries(manifest.tables)) {
    const rows = await query('SELECT COUNT(*) FROM `' + table + '`');
    if (Number(rows[0]?.[0]) !== info.count) throw Error('최종 DB 복원 건수 검증 실패: ' + table);
  }
  const config = path.join(state, 'config/application.yml');
  if (manifest.qaConfig && !fs.existsSync(config)) {
    fs.mkdirSync(path.dirname(config), { recursive: true });
    fs.copyFileSync(qa, config, fs.constants.COPYFILE_EXCL);
  }
  const result = { installed: true, sqlSha256: manifest.sql.sha256, sourceCommit: manifest.sourceCommit, at: new Date().toISOString() };
  await record(marker, result);
  fs.unlinkSync(pending);
  return result;
}
module.exports = { installFinalDataset };
