// Narrow compatibility bridge for the released final-mvp desktop V43 lineage.
// Main already used V43 for account.show_guides and V44 for operations.
// Never repair arbitrary checksums or modify business rows here.
const LEGACY_CHECKSUM = 2029262443;
const MAIN_CHECKSUM = 1824834412;
async function prepareLegacySchema({ query, backup, record }) {
  const tables = await query("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='ictwork' AND table_name='flyway_schema_history'");
  if (tables[0]?.[0] !== '1') return false;
  const rows = await query("SELECT version,script,checksum,success FROM flyway_schema_history WHERE version IN ('43','44') ORDER BY installed_rank");
  const old = rows.find(row => row[0] === '43');
  const canonical = rows.find(row => row[0] === '44');
  if (!old && canonical?.[1] === 'V44__operation_systems.sql' && canonical[2] === String(MAIN_CHECKSUM) && canonical[3] === '1') {
    // Resume after a stopped startup: Flyway still needs to apply account V43.
    return true;
  }
  if (old?.[1] !== 'V43__operation_systems.sql') return false;
  if (canonical || old[2] !== String(LEGACY_CHECKSUM) || old[3] !== '1') {
    throw Error('V43 운영현황 이력이 예상과 다릅니다. 자동 변경 없이 중단합니다.');
  }
  const operationTables = await query("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='ictwork' AND table_name IN ('operation_system','operation_history')");
  if (operationTables[0]?.[0] !== '2') throw Error('운영현황 테이블을 확인할 수 없어 업그레이드를 중단합니다.');
  const saved = await backup(); // Must finish a full logical backup before touching history.
  await record({ phase:'backed-up', backup:saved, from:old, to:['44','V44__operation_systems.sql',String(MAIN_CHECKSUM),'1'] });
  const changed = await query(`START TRANSACTION; UPDATE flyway_schema_history SET version='44',description='operation systems',script='V44__operation_systems.sql',checksum=${MAIN_CHECKSUM} WHERE version='43' AND script='V43__operation_systems.sql' AND checksum=${LEGACY_CHECKSUM} AND success=1; SELECT ROW_COUNT(); COMMIT;`);
  if (changed[0]?.[0] !== '1') throw Error('운영현황 이력 변경 건수가 예상과 다릅니다. 백업을 확인하세요.');
  await record({ phase:'history-mapped', backup:saved, from:old, to:['44','V44__operation_systems.sql',String(MAIN_CHECKSUM),'1'] });
  return true; // One startup with outOfOrder applies the original, unchanged main V43.
}
module.exports = { prepareLegacySchema, LEGACY_CHECKSUM, MAIN_CHECKSUM };
