-- 시연용 장비 데이터 (DEC-080, 명세 밖 시연 도구). scripts/demo-equipment-data.py가 만든 파일이다. 직접 고치지 말고 생성기를 고쳐 다시 돌린다
-- 값은 모두 가상(NFR-002): IP 192.0.2.0/24·198.51.100.0/24, MAC 02-00-00-…, 모델명·시리얼넘버·사업명도 가상
-- 서버 기동 때(local·demo 프로필) 초기 데이터 다음에 돈다. 손대지 않은 것만 채우고, 같은 번호·이름·시리얼넘버·MAC이 있으면 그 줄은 건너뛴다

-- 설치장소 (대대 통신장비실·전산실·지휘통제실, 중대 행정반·통신창고, 반 사무실)

INSERT INTO location (id, department_id, name)
SELECT v.column_0, v.column_1, v.column_2 FROM (VALUES
    ROW(1001, 1, '1대대 통신장비실'),
    ROW(1002, 1, '1대대 전산실'),
    ROW(1003, 1, '1대대 지휘통제실'),
    ROW(1004, 11, '2대대 통신장비실'),
    ROW(1005, 11, '2대대 전산실'),
    ROW(1006, 11, '2대대 지휘통제실'),
    ROW(1007, 2, '1대대 1중대 행정반'),
    ROW(1008, 2, '1대대 1중대 통신창고'),
    ROW(1009, 3, '1대대 2중대 행정반'),
    ROW(1010, 3, '1대대 2중대 통신창고'),
    ROW(1011, 4, '1대대 3중대 행정반'),
    ROW(1012, 4, '1대대 3중대 통신창고'),
    ROW(1013, 12, '2대대 1중대 행정반'),
    ROW(1014, 12, '2대대 1중대 통신창고'),
    ROW(1015, 13, '2대대 2중대 행정반'),
    ROW(1016, 13, '2대대 2중대 통신창고'),
    ROW(1017, 14, '2대대 3중대 행정반'),
    ROW(1018, 14, '2대대 3중대 통신창고'),
    ROW(1019, 5, '1대대 1중대 1반 사무실'),
    ROW(1020, 6, '1대대 1중대 2반 사무실'),
    ROW(1021, 7, '1대대 2중대 1반 사무실'),
    ROW(1022, 8, '1대대 2중대 2반 사무실'),
    ROW(1023, 9, '1대대 3중대 1반 사무실'),
    ROW(1024, 10, '1대대 3중대 2반 사무실'),
    ROW(1025, 15, '2대대 1중대 1반 사무실'),
    ROW(1026, 16, '2대대 1중대 2반 사무실'),
    ROW(1027, 17, '2대대 2중대 1반 사무실'),
    ROW(1028, 18, '2대대 2중대 2반 사무실'),
    ROW(1029, 19, '2대대 3중대 1반 사무실'),
    ROW(1030, 20, '2대대 3중대 2반 사무실')) v
WHERE NOT EXISTS (SELECT 1 FROM location l WHERE l.id = v.column_0 OR l.active_name = v.column_2);

-- 초기 장비 36대: 손대지 않은 장비만 항목을 채운다(V14 임시 값 '미상'·'MIG-번호'면 처음 모델명·시리얼넘버로). 반출 여부·현재 위치는 그대로

UPDATE equipment e JOIN (
  SELECT column_0 AS id, column_1 AS model, column_2 AS serial, column_3, column_4, column_5, column_6, column_7, column_8, column_9, column_10, column_11, column_12, column_13, column_14, column_15, column_16 FROM (VALUES
    ROW(1, 'RT-100', 'SN-RT-0001', '전술무전체계', '1중대 1반 휴대용 무전기 1호', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 1반 사무실'), NULL, '메인보드 1.3', '2023-02-21', '펌웨어', '2.4.0', '2023-09-05', 'BATTLEFIELD', '2023-02-21', '2023년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE'),
    ROW(2, 'GEN-5K', 'SN-GN-0001', '전원체계', '1중대 1반 야전 발전기 1호', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 1반 사무실'), NULL, 'Rev.A', '2023-04-29', NULL, NULL, NULL, NULL, '2023-04-29', '2023년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'UNUSED'),
    ROW(3, 'NB-14', 'SN-NB-0001', '사무자동화체계', '1중대 1반 업무용 노트북 1호', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 1반 사무실'), NULL, 'HW 2.1', '2025-06-05', 'Windows 11 Pro', '24H2', '2026-07-18', 'DEFENSE', '2025-06-05', '2025년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE'),
    ROW(4, 'RT-100', 'SN-RT-0002', '전술무전체계', '1중대 2반 휴대용 무전기 2호', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 2반 사무실'), NULL, 'Rev.C', '2021-07-16', '펌웨어', '2.5.1', '2022-07-28', 'BATTLEFIELD', '2021-07-16', '2021년 전술통신 장비 도입 사업', NULL, 'UNUSED'),
    ROW(5, 'GEN-5K', 'SN-GN-0002', '전원체계', '1중대 2반 야전 발전기 2호', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 2반 사무실'), '배터리 교체 필요', 'Rev.A', '2015-06-27', NULL, NULL, NULL, NULL, '2015-06-27', '2015년 전원 장비 교체 사업', NULL, 'IN_USE'),
    ROW(6, 'NB-14', 'SN-NB-0002', '사무자동화체계', '1중대 2반 업무용 노트북 2호', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 2반 사무실'), NULL, 'Rev.A', '2016-10-26', 'Windows 11 Pro', '24H2', '2017-06-28', 'DEFENSE', '2016-10-26', '2016년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'UNUSED'),
    ROW(7, 'RT-100', 'SN-RT-0003', '전술무전체계', '2중대 1반 휴대용 무전기 3호', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 1반 사무실'), '키보드·마우스 교체(2025)', 'Rev.A', '2015-08-19', '펌웨어', '2.5.1', '2016-03-05', 'BATTLEFIELD', '2015-08-19', '2015년 전술통신 장비 도입 사업', NULL, 'IN_USE'),
    ROW(8, 'GEN-5K', 'SN-GN-0003', '전원체계', '2중대 1반 야전 발전기 3호', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 1반 사무실'), NULL, 'Rev.A', '2021-04-15', NULL, NULL, NULL, NULL, '2021-04-15', '2021년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'IN_USE'),
    ROW(9, 'NB-14', 'SN-NB-0003', '사무자동화체계', '2중대 1반 업무용 노트북 3호', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 1반 사무실'), '정기 정비 입고', '메인보드 1.3', '2018-12-16', 'Windows 11 Pro', '23H2', '2018-12-29', 'DEFENSE', '2018-12-16', '2018년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'MAINTENANCE'),
    ROW(10, 'RT-100', 'SN-RT-0004', '전술무전체계', '2중대 2반 휴대용 무전기 4호', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 2반 사무실'), '정기 점검 대상', 'HW 2.1', '2024-08-15', '펌웨어', '2.4.0', '2025-02-05', 'BATTLEFIELD', '2024-08-15', '2024년 전술통신 장비 도입 사업', NULL, 'IN_USE'),
    ROW(11, 'GEN-5K', 'SN-GN-0004', '전원체계', '2중대 2반 야전 발전기 4호', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 2반 사무실'), NULL, 'HW 1.0', '2021-12-12', NULL, NULL, NULL, NULL, '2021-12-12', '2021년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'IN_USE'),
    ROW(12, 'NB-14', 'SN-NB-0004', '사무자동화체계', '2중대 2반 업무용 노트북 4호', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 2반 사무실'), NULL, '메인보드 1.3', '2018-05-09', 'Windows 11 Pro', '23H2', '2018-07-03', 'DEFENSE', '2018-05-09', '2018년 사무자동화 장비 보강 사업', NULL, 'IN_USE'),
    ROW(13, 'RT-100', 'SN-RT-0005', '전술무전체계', '3중대 1반 휴대용 무전기 5호', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 1반 사무실'), NULL, 'HW 2.1', '2025-11-28', '펌웨어', '2.5.1', '2026-09-12', 'BATTLEFIELD', '2025-11-28', '2025년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE'),
    ROW(14, 'GEN-5K', 'SN-GN-0005', '전원체계', '3중대 1반 야전 발전기 5호', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 1반 사무실'), NULL, 'Rev.B', '2024-06-09', NULL, NULL, NULL, NULL, '2024-06-09', '2024년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'UNUSED'),
    ROW(15, 'NB-14', 'SN-NB-0005', '사무자동화체계', '3중대 1반 업무용 노트북 5호', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 1반 사무실'), NULL, '메인보드 1.3', '2022-06-21', 'Windows 11 Pro', '23H2', '2022-12-19', 'DEFENSE', '2022-06-21', '2022년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE'),
    ROW(16, 'RT-100', 'SN-RT-0006', '전술무전체계', '3중대 2반 휴대용 무전기 6호', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 2반 사무실'), NULL, 'Rev.C', '2019-04-11', '펌웨어', '2.5.1', '2020-05-07', 'BATTLEFIELD', '2019-04-11', '2019년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'UNUSED'),
    ROW(17, 'GEN-5K', 'SN-GN-0006', '전원체계', '3중대 2반 야전 발전기 6호', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 2반 사무실'), NULL, 'HW 2.1', '2024-03-10', NULL, NULL, NULL, NULL, '2024-03-10', '2024년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'UNUSED'),
    ROW(18, 'NB-14', 'SN-NB-0006', '사무자동화체계', '3중대 2반 업무용 노트북 6호', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 2반 사무실'), NULL, 'Rev.B', '2017-04-02', 'Windows 11 Pro', '23H2', '2019-09-09', 'DEFENSE', '2017-04-02', '2017년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE'),
    ROW(19, 'RT-100', 'SN-RT-0007', '전술무전체계', '1중대 1반 휴대용 무전기 7호', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 1반 사무실'), NULL, 'HW 2.1', '2022-10-23', '펌웨어', '2.5.1', '2023-01-25', 'BATTLEFIELD', '2022-10-23', '2022년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'UNUSED'),
    ROW(20, 'GEN-5K', 'SN-GN-0007', '전원체계', '1중대 1반 야전 발전기 7호', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 1반 사무실'), NULL, 'Rev.C', '2025-06-17', NULL, NULL, NULL, NULL, '2025-06-17', '2025년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'IN_USE'),
    ROW(21, 'NB-14', 'SN-NB-0007', '사무자동화체계', '1중대 1반 업무용 노트북 7호', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 1반 사무실'), '키보드·마우스 교체(2025)', '메인보드 1.3', '2016-02-13', 'Windows 11 Pro', '24H2', '2016-05-25', 'DEFENSE', '2016-02-13', '2016년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE'),
    ROW(22, 'RT-100', 'SN-RT-0008', '전술무전체계', '1중대 2반 휴대용 무전기 8호', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 2반 사무실'), NULL, 'HW 2.1', '2022-07-16', '펌웨어', '2.5.1', '2024-11-03', 'BATTLEFIELD', '2022-07-16', '2022년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE'),
    ROW(23, 'GEN-5K', 'SN-GN-0008', '전원체계', '1중대 2반 야전 발전기 8호', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 2반 사무실'), NULL, 'Rev.C', '2023-07-30', NULL, NULL, NULL, NULL, '2023-07-30', '2023년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'IN_USE'),
    ROW(24, 'NB-14', 'SN-NB-0008', '사무자동화체계', '1중대 2반 업무용 노트북 8호', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 2반 사무실'), '정기 점검 대상', 'HW 1.0', '2018-08-30', 'Windows 11 Pro', '24H2', '2019-12-16', 'DEFENSE', '2018-08-30', '2018년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE'),
    ROW(25, 'RT-100', 'SN-RT-0009', '전술무전체계', '2중대 1반 휴대용 무전기 9호', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 1반 사무실'), '예비품으로 보관', '메인보드 1.3', '2018-03-22', '펌웨어', '2.5.1', '2018-11-24', 'BATTLEFIELD', '2018-03-22', '2018년 전술통신 장비 도입 사업', NULL, 'IN_USE'),
    ROW(26, 'GEN-5K', 'SN-GN-0009', '전원체계', '2중대 1반 야전 발전기 9호', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 1반 사무실'), NULL, 'Rev.A', '2022-03-14', NULL, NULL, NULL, NULL, '2022-03-14', '2022년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'IN_USE'),
    ROW(27, 'NB-14', 'SN-NB-0009', '사무자동화체계', '2중대 1반 업무용 노트북 9호', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 1반 사무실'), NULL, 'Rev.C', '2024-09-12', 'Windows 11 Pro', '23H2', '2026-09-30', 'DEFENSE', '2024-09-12', '2024년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE'),
    ROW(28, 'RT-100', 'SN-RT-0010', '전술무전체계', '2중대 2반 휴대용 무전기 10호', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 2반 사무실'), NULL, 'Rev.B', '2017-07-26', '펌웨어', '2.4.0', '2019-04-11', 'BATTLEFIELD', '2017-07-26', '2017년 전술통신 장비 도입 사업', NULL, 'IN_USE'),
    ROW(29, 'GEN-5K', 'SN-GN-0010', '전원체계', '2중대 2반 야전 발전기 10호', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 2반 사무실'), NULL, 'Rev.A', '2021-05-31', NULL, NULL, NULL, NULL, '2021-05-31', '2021년 전원 장비 교체 사업', NULL, 'IN_USE'),
    ROW(30, 'NB-14', 'SN-NB-0010', '사무자동화체계', '2중대 2반 업무용 노트북 10호', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 2반 사무실'), NULL, 'HW 2.1', '2018-02-23', 'Windows 11 Pro', '23H2', '2019-12-11', 'DEFENSE', '2018-02-23', '2018년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE'),
    ROW(31, 'RT-100', 'SN-RT-0011', '전술무전체계', '3중대 1반 휴대용 무전기 11호', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 1반 사무실'), NULL, '메인보드 1.3', '2016-01-01', '펌웨어', '2.5.1', '2016-08-04', 'BATTLEFIELD', '2016-01-01', '2016년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE'),
    ROW(32, 'GEN-5K', 'SN-GN-0011', '전원체계', '3중대 1반 야전 발전기 11호', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 1반 사무실'), NULL, 'HW 2.1', '2022-02-10', NULL, NULL, NULL, NULL, '2022-02-10', '2022년 전원 장비 교체 사업', NULL, 'IN_USE'),
    ROW(33, 'NB-14', 'SN-NB-0011', '사무자동화체계', '3중대 1반 업무용 노트북 11호', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 1반 사무실'), NULL, '메인보드 1.3', '2018-06-21', 'Windows 11 Pro', '24H2', '2018-09-25', 'DEFENSE', '2018-06-21', '2018년 사무자동화 장비 보강 사업', NULL, 'IN_USE'),
    ROW(34, 'RT-100', 'SN-RT-0012', '전술무전체계', '3중대 2반 휴대용 무전기 12호', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 2반 사무실'), NULL, '메인보드 1.3', '2018-07-20', '펌웨어', '2.4.0', '2018-11-30', 'BATTLEFIELD', '2018-07-20', '2018년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE'),
    ROW(35, 'GEN-5K', 'SN-GN-0012', '전원체계', '3중대 2반 야전 발전기 12호', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 2반 사무실'), NULL, 'Rev.A', '2024-10-06', NULL, NULL, NULL, NULL, '2024-10-06', '2024년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'IN_USE'),
    ROW(36, 'NB-14', 'SN-NB-0012', '사무자동화체계', '3중대 2반 업무용 노트북 12호', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 2반 사무실'), NULL, 'Rev.B', '2025-08-10', 'Windows 11 Pro', '23H2', '2026-09-30', 'DEFENSE', '2025-08-10', '2025년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE')) x
) v ON e.id = v.id
SET e.model_name = v.model, e.serial_number = v.serial,
    e.system_name = v.column_3,
    e.common_name = v.column_4,
    e.storage_location_id = v.column_5,
    e.memo = v.column_6,
    e.hw_version = v.column_7,
    e.hw_installed_on = v.column_8,
    e.os_type = v.column_9,
    e.os_version = v.column_10,
    e.os_installed_on = v.column_11,
    e.network_kind = v.column_12,
    e.introduced_on = v.column_13,
    e.intro_project = v.column_14,
    e.maintenance_project = v.column_15,
    e.status = IF(e.status = 'UNUSED', v.column_16, e.status),
    e.edited_at = TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
WHERE (e.model_name = v.model AND e.serial_number = v.serial OR e.model_name = '미상' AND e.serial_number = CONCAT('MIG-', e.id))
  AND e.system_name IS NULL AND e.common_name IS NULL AND e.memo IS NULL AND e.hw_version IS NULL AND e.os_type IS NULL
  AND e.introduced_on IS NULL AND e.storage_location_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM equipment_software s WHERE s.equipment_id = e.id)
  AND NOT EXISTS (SELECT 1 FROM equipment_network n WHERE n.equipment_id = e.id)
  AND NOT EXISTS (SELECT 1 FROM (SELECT id, model_name, serial_number FROM equipment WHERE status <> 'DISPOSED') o
                  WHERE o.model_name = v.model AND o.serial_number = v.serial AND o.id <> e.id);

-- 새 장비: 상태 6가지와 사람이 정한 반출을 섞는다. 같은 번호나 불용이 아닌 같은 모델명·시리얼넘버가 있으면 건너뜀

INSERT INTO equipment (id, name, department_id, model_name, serial_number, system_name, common_name, storage_location_id, memo, hw_version, hw_installed_on, os_type, os_version, os_installed_on, network_kind, introduced_on, intro_project, maintenance_project, status, carry_out, current_department_id, current_place, carry_out_reason, carry_out_by_account_id, carry_out_at, edited_at)
SELECT * FROM (
SELECT 1001 AS id, '행정용 PC 1호' AS name, 5 AS department_id, 'DT-7400' AS model_name, 'DT7-21-00001' AS serial_number, '사무자동화체계' AS system_name, '1중대 1반 행정용 PC 1' AS common_name, (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 1반 사무실') AS storage_location_id, NULL AS memo, '메인보드 1.3' AS hw_version, '2021-04-19' AS hw_installed_on, 'Windows 11 Pro' AS os_type, '23H2' AS os_version, '2021-08-11' AS os_installed_on, 'DEFENSE' AS network_kind, '2021-04-19' AS introduced_on, '2021년 사무자동화 장비 보강 사업' AS intro_project, '2026년 정보통신장비 통합 유지보수' AS maintenance_project, 'IN_USE' AS status, 'NONE' AS carry_out, NULL AS current_department_id, NULL AS current_place, NULL AS carry_out_reason, NULL AS carry_out_by_account_id, NULL AS carry_out_at, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY AS edited_at
UNION ALL SELECT 1002, '행정용 PC 2호', 5, 'DT-7400', 'DT7-23-00002', '사무자동화체계', '1중대 1반 행정용 PC 2', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 1반 사무실'), NULL, 'HW 2.1', '2023-01-18', 'Windows 11 Pro', '23H2', '2025-02-10', 'DEFENSE', '2023-01-18', '2023년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1003, '업무용 노트북 13호', 5, 'NB-15', 'NB5-23-00013', '사무자동화체계', '1중대 1반 업무용 노트북 13', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 1반 사무실'), NULL, 'HW 2.1', '2023-07-05', 'Windows 11 Pro', '24H2', '2024-03-22', 'DEFENSE', '2023-07-05', '2023년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1004, '레이저 프린터 1호', 5, 'PR-M400', 'PRM-16-00001', '사무자동화체계', '1중대 1반 레이저 프린터 1', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 1반 사무실'), NULL, 'HW 2.1', '2016-04-05', '펌웨어', '3.3.0', '2018-08-05', 'DEFENSE', '2016-04-05', '2016년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'UNUSED', 'OUT', 10, NULL, '타부서 대여: 훈련 지원', (SELECT MIN(a.id) FROM account a JOIN personnel p ON p.id = a.personnel_id WHERE p.department_id = 5 AND a.active), TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 20 DAY, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 20 DAY
UNION ALL SELECT 1005, '차량용 무전기 1호', 5, 'VR-50', 'V5-16-00001', '전술무전체계', '1중대 1반 차량용 무전기 1', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 통신창고'), NULL, 'HW 1.0', '2016-07-09', '펌웨어', '1.9.3', '2017-10-07', 'BATTLEFIELD', '2016-07-09', '2016년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1006, '소형 스위치 1호', 5, 'SW-2400', 'S24-19-00001', '부대 네트워크 기반체계', '1중대 1반 소형 스위치 1', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 1반 사무실'), '외부 정비 입고, 부품 수급 대기', 'Rev.C', '2019-10-12', 'NOS', '4.2.0', '2021-02-04', 'DEFENSE', '2019-10-12', '2019년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'MAINTENANCE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1007, '소형 발전기 1호', 5, 'GEN-2K', 'G2-18-00013', '전원체계', '1중대 1반 소형 발전기 1', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 통신창고'), NULL, 'Rev.B', '2018-04-17', NULL, NULL, NULL, NULL, '2018-04-17', '2018년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1008, '행정용 PC 3호', 6, 'DT-7400', 'DT7-15-00003', '사무자동화체계', '1중대 2반 행정용 PC 3', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 2반 사무실'), NULL, 'Rev.B', '2015-05-14', 'Windows 11 Pro', '23H2', '2017-09-17', 'DEFENSE', '2015-05-14', '2015년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1009, '업무용 노트북 14호', 6, 'NB-15', 'NB5-24-00014', '사무자동화체계', '1중대 2반 업무용 노트북 14', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 2반 사무실'), '예비품으로 보관', 'Rev.B', '2024-07-19', 'Windows 11 Pro', '24H2', '2026-01-01', 'DEFENSE', '2024-07-19', '2024년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1010, '휴대용 무전기 13호', 6, 'RT-200', 'R2-19-00013', '전술무전체계', '1중대 2반 휴대용 무전기 13', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 통신창고'), NULL, 'HW 2.1', '2019-02-21', '펌웨어', '2.4.0', '2020-12-28', 'BATTLEFIELD', '2019-02-21', '2019년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'OUT', NULL, '야외 훈련장', '야외 훈련장 설치', (SELECT MIN(a.id) FROM account a JOIN personnel p ON p.id = a.personnel_id WHERE p.department_id = 6 AND a.active), TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 13 DAY, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 13 DAY
UNION ALL SELECT 1011, '차량용 무전기 2호', 6, 'VR-50', 'V5-21-00002', '전술무전체계', '1중대 2반 차량용 무전기 2', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 통신창고'), '화면 출력 안 됨', 'HW 2.1', '2021-08-01', '펌웨어', '1.9.3', '2022-03-26', 'BATTLEFIELD', '2021-08-01', '2021년 전술통신 장비 도입 사업', NULL, 'BROKEN', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1012, '소형 스위치 2호', 6, 'SW-2400', 'S24-23-00002', '부대 네트워크 기반체계', '1중대 2반 소형 스위치 2', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 2반 사무실'), '외부 정비 입고, 부품 수급 대기', 'Rev.C', '2023-09-28', 'NOS', '4.1.2', '2024-03-26', 'DEFENSE', '2023-09-28', '2023년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'MAINTENANCE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1013, '무정전 전원장치 1호', 6, 'UPS-3K', 'UP3-22-00001', '전원체계', '1중대 2반 무정전 전원장치 1', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 2반 사무실'), NULL, 'Rev.C', '2022-09-09', NULL, NULL, NULL, NULL, '2022-09-09', '2022년 전원 장비 교체 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1014, '소형 발전기 2호', 6, 'GEN-2K', 'G2-15-00014', '전원체계', '1중대 2반 소형 발전기 2', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 통신창고'), NULL, 'HW 1.0', '2015-10-02', NULL, NULL, NULL, NULL, '2015-10-02', '2015년 전원 장비 교체 사업', NULL, 'IN_USE', 'OUT', 10, '대대 강당', '행사 지원 대여', (SELECT MIN(a.id) FROM account a JOIN personnel p ON p.id = a.personnel_id WHERE p.department_id = 6 AND a.active), TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 19 DAY, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 19 DAY
UNION ALL SELECT 1015, '휴대용 무전기 14호', 7, 'RT-200', 'R2-21-00014', '전술무전체계', '2중대 1반 휴대용 무전기 14', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 통신창고'), NULL, 'Rev.B', '2021-08-04', '펌웨어', '2.4.0', '2022-10-28', 'BATTLEFIELD', '2021-08-04', '2021년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1016, '차량용 무전기 3호', 7, 'VR-50', 'V5-15-00003', '전술무전체계', '2중대 1반 차량용 무전기 3', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 통신창고'), NULL, 'Rev.A', '2015-01-16', '펌웨어', '1.9.3', '2015-12-15', 'BATTLEFIELD', '2015-01-16', '2015년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1017, '소형 스위치 3호', 7, 'SW-2400', 'S24-18-00003', '부대 네트워크 기반체계', '2중대 1반 소형 스위치 3', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 1반 사무실'), NULL, 'Rev.A', '2018-05-26', 'NOS', '4.1.2', '2018-11-21', 'DEFENSE', '2018-05-26', '2018년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1018, '무정전 전원장치 2호', 7, 'UPS-3K', 'UP3-19-00002', '전원체계', '2중대 1반 무정전 전원장치 2', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 1반 사무실'), '케이블 정리 완료', 'Rev.A', '2019-10-17', NULL, NULL, NULL, NULL, '2019-10-17', '2019년 전원 장비 교체 사업', NULL, 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1019, '소형 발전기 3호', 7, 'GEN-2K', 'G2-25-00015', '전원체계', '2중대 1반 소형 발전기 3', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 통신창고'), NULL, '메인보드 1.3', '2025-06-09', NULL, NULL, NULL, NULL, '2025-06-09', '2025년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1020, '전술 태블릿 1호', 7, 'TB-10', 'TB1-15-00001', '전장정보 단말체계', '2중대 1반 전술 태블릿 1', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 통신창고'), NULL, '메인보드 1.3', '2015-12-10', 'Android', '13', '2017-07-12', 'BATTLEFIELD', '2015-12-10', '2015년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'OUT', 10, NULL, '교육 지원 대여', (SELECT MIN(a.id) FROM account a JOIN personnel p ON p.id = a.personnel_id WHERE p.department_id = 7 AND a.active), TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 6 DAY, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 6 DAY
UNION ALL SELECT 1021, '문서 작업용 단독 PC 1호', 7, 'DT-5200', 'DT5-18-00001', '사무자동화체계', '2중대 1반 문서 작업용 단독 PC 1', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 1반 사무실'), NULL, 'Rev.C', '2018-09-17', 'Windows 10 Pro', '22H2', '2019-10-05', 'STANDALONE', '2018-09-17', '2018년 사무자동화 장비 보강 사업', NULL, 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1022, '행정용 PC 4호', 8, 'DT-7400', 'DT7-15-00004', '사무자동화체계', '2중대 2반 행정용 PC 4', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 2반 사무실'), NULL, '메인보드 1.3', '2015-06-21', 'Windows 11 Pro', '23H2', '2017-11-20', 'DEFENSE', '2015-06-21', '2015년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1023, '행정용 PC 5호', 8, 'DT-7400', 'DT7-21-00005', '사무자동화체계', '2중대 2반 행정용 PC 5', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 2반 사무실'), '케이블 정리 완료', 'HW 2.1', '2021-12-14', 'Windows 11 Pro', '24H2', '2022-08-06', 'DEFENSE', '2021-12-14', '2021년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1024, '업무용 노트북 15호', 8, 'NB-15', 'NB5-22-00015', '사무자동화체계', '2중대 2반 업무용 노트북 15', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 2반 사무실'), NULL, 'Rev.B', '2022-07-08', 'Windows 11 Pro', '24H2', '2024-07-10', 'DEFENSE', '2022-07-08', '2022년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1025, '휴대용 무전기 15호', 8, 'RT-200', 'R2-22-00015', '전술무전체계', '2중대 2반 휴대용 무전기 15', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 통신창고'), NULL, 'HW 1.0', '2022-03-21', '펌웨어', '2.5.1', '2022-10-07', 'BATTLEFIELD', '2022-03-21', '2022년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'OUT', NULL, '외부 정비업체(가상)', '외부 수리 업체 입고', (SELECT MIN(a.id) FROM account a JOIN personnel p ON p.id = a.personnel_id WHERE p.department_id = 8 AND a.active), TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 19 DAY, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 19 DAY
UNION ALL SELECT 1026, '차량용 무전기 4호', 8, 'VR-50', 'V5-21-00004', '전술무전체계', '2중대 2반 차량용 무전기 4', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 통신창고'), '키보드·마우스 교체(2025)', 'HW 1.0', '2021-06-09', '펌웨어', '1.9.3', '2023-02-05', 'BATTLEFIELD', '2021-06-09', '2021년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1027, '소형 발전기 4호', 8, 'GEN-2K', 'G2-16-00016', '전원체계', '2중대 2반 소형 발전기 4', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 통신창고'), '외부 정비 입고, 부품 수급 대기', 'Rev.C', '2016-06-23', NULL, NULL, NULL, NULL, '2016-06-23', '2016년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'MAINTENANCE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1028, '문서 작업용 단독 PC 2호', 8, 'DT-5200', 'DT5-16-00002', '사무자동화체계', '2중대 2반 문서 작업용 단독 PC 2', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 2반 사무실'), '키보드·마우스 교체(2025)', 'Rev.B', '2016-05-30', 'Windows 10 Pro', '22H2', '2017-07-11', 'STANDALONE', '2016-05-30', '2016년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1029, '행정용 PC 6호', 9, 'DT-7400', 'DT7-21-00006', '사무자동화체계', '3중대 1반 행정용 PC 6', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 1반 사무실'), NULL, 'Rev.B', '2021-01-25', 'Windows 11 Pro', '23H2', '2022-05-21', 'DEFENSE', '2021-01-25', '2021년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1030, '행정용 PC 7호', 9, 'DT-7400', 'DT7-16-00007', '사무자동화체계', '3중대 1반 행정용 PC 7', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 1반 사무실'), NULL, 'HW 1.0', '2016-07-21', 'Windows 11 Pro', '24H2', '2018-12-20', 'DEFENSE', '2016-07-21', '2016년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1031, '업무용 노트북 16호', 9, 'NB-15', 'NB5-25-00016', '사무자동화체계', '3중대 1반 업무용 노트북 16', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 1반 사무실'), NULL, '메인보드 1.3', '2025-01-20', 'Windows 11 Pro', '23H2', '2026-01-23', 'DEFENSE', '2025-01-20', '2025년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1032, '휴대용 무전기 16호', 9, 'RT-200', 'R2-15-00016', '전술무전체계', '3중대 1반 휴대용 무전기 16', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 통신창고'), NULL, 'HW 2.1', '2015-03-27', '펌웨어', '2.5.1', '2017-09-01', 'BATTLEFIELD', '2015-03-27', '2015년 전술통신 장비 도입 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1033, '휴대용 무전기 17호', 9, 'RT-200', 'R2-18-00017', '전술무전체계', '3중대 1반 휴대용 무전기 17', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 통신창고'), '전원 불량, 수리 의뢰 예정', 'HW 1.0', '2018-05-14', '펌웨어', '2.4.0', '2020-01-17', 'BATTLEFIELD', '2018-05-14', '2018년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'BROKEN', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1034, '차량용 무전기 5호', 9, 'VR-50', 'V5-20-00005', '전술무전체계', '3중대 1반 차량용 무전기 5', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 통신창고'), NULL, 'Rev.C', '2020-03-12', '펌웨어', '1.9.3', '2020-11-04', 'BATTLEFIELD', '2020-03-12', '2020년 전술통신 장비 도입 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1035, '무정전 전원장치 3호', 9, 'UPS-3K', 'UP3-18-00003', '전원체계', '3중대 1반 무정전 전원장치 3', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 1반 사무실'), NULL, 'Rev.C', '2018-06-14', NULL, NULL, NULL, NULL, '2018-06-14', '2018년 전원 장비 교체 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1036, '행정용 PC 8호', 10, 'DT-7400', 'DT7-25-00008', '사무자동화체계', '3중대 2반 행정용 PC 8', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 2반 사무실'), NULL, 'Rev.A', '2025-07-20', 'Windows 11 Pro', '24H2', '2026-09-30', 'DEFENSE', '2025-07-20', '2025년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1037, '행정용 PC 9호', 10, 'DT-7400', 'DT7-16-00009', '사무자동화체계', '3중대 2반 행정용 PC 9', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 2반 사무실'), '예비품으로 보관', 'HW 2.1', '2016-08-13', 'Windows 11 Pro', '24H2', '2017-12-19', 'DEFENSE', '2016-08-13', '2016년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1038, '업무용 노트북 17호', 10, 'NB-15', 'NB5-20-00017', '사무자동화체계', '3중대 2반 업무용 노트북 17', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 2반 사무실'), NULL, 'Rev.A', '2020-12-16', 'Windows 11 Pro', '24H2', '2021-08-01', 'DEFENSE', '2020-12-16', '2020년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1039, '레이저 프린터 2호', 10, 'PR-M400', 'PRM-19-00002', '사무자동화체계', '3중대 2반 레이저 프린터 2', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 2반 사무실'), NULL, 'HW 1.0', '2019-04-11', '펌웨어', '3.2.1', '2020-03-06', 'DEFENSE', '2019-04-11', '2019년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1040, '소형 스위치 4호', 10, 'SW-2400', 'S24-24-00004', '부대 네트워크 기반체계', '3중대 2반 소형 스위치 4', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 2반 사무실'), NULL, 'Rev.A', '2024-08-20', 'NOS', '4.2.0', '2026-05-09', 'DEFENSE', '2024-08-20', '2024년 부대 네트워크 고도화 사업', NULL, 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1041, '무정전 전원장치 4호', 10, 'UPS-3K', 'UP3-22-00004', '전원체계', '3중대 2반 무정전 전원장치 4', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 2반 사무실'), NULL, '메인보드 1.3', '2022-05-09', NULL, NULL, NULL, NULL, '2022-05-09', '2022년 전원 장비 교체 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1042, '전술 태블릿 2호', 10, 'TB-10', 'TB1-18-00002', '전장정보 단말체계', '3중대 2반 전술 태블릿 2', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 통신창고'), '내용연수 경과, 불용 심의 대기', 'Rev.C', '2018-04-18', 'Android', '14', '2019-12-29', 'BATTLEFIELD', '2018-04-18', '2018년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'DISPOSAL_PENDING', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1043, '업무용 노트북 18호', 15, 'NB-15', 'NB5-19-00018', '사무자동화체계', '1중대 1반 업무용 노트북 18', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 1반 사무실'), '외부 정비 입고, 부품 수급 대기', '메인보드 1.3', '2019-10-01', 'Windows 11 Pro', '24H2', '2021-08-16', 'DEFENSE', '2019-10-01', '2019년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'MAINTENANCE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1044, '레이저 프린터 3호', 15, 'PR-M400', 'PRM-16-00003', '사무자동화체계', '1중대 1반 레이저 프린터 3', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 1반 사무실'), '내용연수 경과로 불용', 'Rev.C', '2016-11-29', '펌웨어', '3.3.0', '2017-05-16', 'DEFENSE', '2016-11-29', '2016년 사무자동화 장비 보강 사업', NULL, 'DISPOSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1045, '휴대용 무전기 18호', 15, 'RT-200', 'R2-23-00018', '전술무전체계', '1중대 1반 휴대용 무전기 18', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 통신창고'), '배터리 교체 필요', 'Rev.C', '2023-04-18', '펌웨어', '2.5.1', '2025-01-25', 'BATTLEFIELD', '2023-04-18', '2023년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1046, '휴대용 무전기 19호', 15, 'RT-200', 'R2-18-00019', '전술무전체계', '1중대 1반 휴대용 무전기 19', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 통신창고'), '내용연수 경과로 불용', 'Rev.C', '2018-11-10', '펌웨어', '2.4.0', '2020-07-11', 'BATTLEFIELD', '2018-11-10', '2018년 전술통신 장비 도입 사업', NULL, 'DISPOSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1047, '차량용 무전기 6호', 15, 'VR-50', 'V5-19-00006', '전술무전체계', '1중대 1반 차량용 무전기 6', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 통신창고'), NULL, '메인보드 1.3', '2019-05-09', '펌웨어', '1.9.3', '2020-11-24', 'BATTLEFIELD', '2019-05-09', '2019년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1048, '소형 발전기 5호', 15, 'GEN-2K', 'G2-17-00017', '전원체계', '1중대 1반 소형 발전기 5', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 통신창고'), '모니터 2대 연결', 'Rev.A', '2017-08-11', NULL, NULL, NULL, NULL, '2017-08-11', '2017년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1049, '문서 작업용 단독 PC 3호', 15, 'DT-5200', 'DT5-15-00003', '사무자동화체계', '1중대 1반 문서 작업용 단독 PC 3', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 1반 사무실'), NULL, 'Rev.B', '2015-11-26', 'Windows 10 Pro', '22H2', '2016-12-25', 'STANDALONE', '2015-11-26', '2015년 사무자동화 장비 보강 사업', NULL, 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1050, '행정용 PC 10호', 16, 'DT-7400', 'DT7-22-00010', '사무자동화체계', '1중대 2반 행정용 PC 10', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 2반 사무실'), NULL, 'HW 1.0', '2022-05-25', 'Windows 11 Pro', '23H2', '2023-01-07', 'DEFENSE', '2022-05-25', '2022년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1051, '행정용 PC 11호', 16, 'DT-7400', 'DT7-20-00011', '사무자동화체계', '1중대 2반 행정용 PC 11', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 2반 사무실'), NULL, '메인보드 1.3', '2020-05-18', 'Windows 11 Pro', '23H2', '2021-06-21', 'DEFENSE', '2020-05-18', '2020년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1052, '업무용 노트북 19호', 16, 'NB-15', 'NB5-20-00019', '사무자동화체계', '1중대 2반 업무용 노트북 19', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 2반 사무실'), NULL, '메인보드 1.3', '2020-05-08', 'Windows 11 Pro', '23H2', '2020-09-19', 'DEFENSE', '2020-05-08', '2020년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'OUT', NULL, '제조사 서비스센터(가상)', '제조사 점검 입고', (SELECT MIN(a.id) FROM account a JOIN personnel p ON p.id = a.personnel_id WHERE p.department_id = 16 AND a.active), TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1053, '휴대용 무전기 20호', 16, 'RT-200', 'R2-18-00020', '전술무전체계', '1중대 2반 휴대용 무전기 20', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 통신창고'), NULL, 'HW 1.0', '2018-09-08', '펌웨어', '2.5.1', '2020-08-25', 'BATTLEFIELD', '2018-09-08', '2018년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1054, '소형 스위치 5호', 16, 'SW-2400', 'S24-25-00005', '부대 네트워크 기반체계', '1중대 2반 소형 스위치 5', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 2반 사무실'), NULL, 'Rev.B', '2025-07-19', 'NOS', '4.1.2', '2026-09-30', 'DEFENSE', '2025-07-19', '2025년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1055, '전술 태블릿 3호', 16, 'TB-10', 'TB1-21-00003', '전장정보 단말체계', '1중대 2반 전술 태블릿 3', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 통신창고'), NULL, 'Rev.B', '2021-09-19', 'Android', '13', '2024-02-08', 'BATTLEFIELD', '2021-09-19', '2021년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1056, '문서 작업용 단독 PC 4호', 16, 'DT-5200', 'DT5-17-00004', '사무자동화체계', '1중대 2반 문서 작업용 단독 PC 4', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 2반 사무실'), NULL, '메인보드 1.3', '2017-03-15', 'Windows 10 Pro', '22H2', '2017-06-17', 'STANDALONE', '2017-03-15', '2017년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1057, '행정용 PC 12호', 17, 'DT-7400', 'DT7-22-00012', '사무자동화체계', '2중대 1반 행정용 PC 12', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 1반 사무실'), '배터리 교체 필요', 'HW 2.1', '2022-06-16', 'Windows 11 Pro', '23H2', '2023-09-06', 'DEFENSE', '2022-06-16', '2022년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1058, '업무용 노트북 20호', 17, 'NB-15', 'NB5-24-00020', '사무자동화체계', '2중대 1반 업무용 노트북 20', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 1반 사무실'), NULL, 'Rev.B', '2024-03-25', 'Windows 11 Pro', '24H2', '2026-04-28', 'DEFENSE', '2024-03-25', '2024년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1059, '레이저 프린터 4호', 17, 'PR-M400', 'PRM-21-00004', '사무자동화체계', '2중대 1반 레이저 프린터 4', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 1반 사무실'), NULL, '메인보드 1.3', '2021-12-03', '펌웨어', '3.3.0', '2023-03-14', 'DEFENSE', '2021-12-03', '2021년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1060, '휴대용 무전기 21호', 17, 'RT-200', 'R2-15-00021', '전술무전체계', '2중대 1반 휴대용 무전기 21', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 통신창고'), NULL, '메인보드 1.3', '2015-01-17', '펌웨어', '2.4.0', '2017-05-10', 'BATTLEFIELD', '2015-01-17', '2015년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1061, '무정전 전원장치 5호', 17, 'UPS-3K', 'UP3-16-00005', '전원체계', '2중대 1반 무정전 전원장치 5', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 1반 사무실'), NULL, 'Rev.C', '2016-05-16', NULL, NULL, NULL, NULL, '2016-05-16', '2016년 전원 장비 교체 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1062, '소형 발전기 6호', 17, 'GEN-2K', 'G2-25-00018', '전원체계', '2중대 1반 소형 발전기 6', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 통신창고'), NULL, 'Rev.A', '2025-03-04', NULL, NULL, NULL, NULL, '2025-03-04', '2025년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1063, '문서 작업용 단독 PC 5호', 17, 'DT-5200', 'DT5-18-00005', '사무자동화체계', '2중대 1반 문서 작업용 단독 PC 5', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 1반 사무실'), NULL, 'HW 2.1', '2018-03-14', 'Windows 10 Pro', '22H2', '2018-05-17', 'STANDALONE', '2018-03-14', '2018년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1064, '행정용 PC 13호', 18, 'DT-7400', 'DT7-21-00013', '사무자동화체계', '2중대 2반 행정용 PC 13', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 2반 사무실'), NULL, 'Rev.B', '2021-07-15', 'Windows 11 Pro', '24H2', '2023-08-05', 'DEFENSE', '2021-07-15', '2021년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1065, '레이저 프린터 5호', 18, 'PR-M400', 'PRM-25-00005', '사무자동화체계', '2중대 2반 레이저 프린터 5', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 2반 사무실'), NULL, 'HW 2.1', '2025-02-07', '펌웨어', '3.3.0', '2025-07-12', 'DEFENSE', '2025-02-07', '2025년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1066, '차량용 무전기 7호', 18, 'VR-50', 'V5-19-00007', '전술무전체계', '2중대 2반 차량용 무전기 7', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 통신창고'), NULL, 'HW 1.0', '2019-05-09', '펌웨어', '1.9.3', '2020-07-02', 'BATTLEFIELD', '2019-05-09', '2019년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1067, '소형 스위치 6호', 18, 'SW-2400', 'S24-19-00006', '부대 네트워크 기반체계', '2중대 2반 소형 스위치 6', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 2반 사무실'), NULL, 'Rev.B', '2019-06-29', 'NOS', '4.1.2', '2019-11-29', 'DEFENSE', '2019-06-29', '2019년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1068, '소형 발전기 7호', 18, 'GEN-2K', 'G2-22-00019', '전원체계', '2중대 2반 소형 발전기 7', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 통신창고'), NULL, '메인보드 1.3', '2022-08-11', NULL, NULL, NULL, NULL, '2022-08-11', '2022년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1069, '전술 태블릿 4호', 18, 'TB-10', 'TB1-17-00004', '전장정보 단말체계', '2중대 2반 전술 태블릿 4', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 통신창고'), NULL, 'HW 1.0', '2017-07-15', 'Android', '14', '2019-11-14', 'BATTLEFIELD', '2017-07-15', '2017년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1070, '문서 작업용 단독 PC 6호', 18, 'DT-5200', 'DT5-19-00006', '사무자동화체계', '2중대 2반 문서 작업용 단독 PC 6', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 2반 사무실'), NULL, 'Rev.A', '2019-07-18', 'Windows 10 Pro', '22H2', '2020-09-19', 'STANDALONE', '2019-07-18', '2019년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1071, '업무용 노트북 21호', 19, 'NB-15', 'NB5-22-00021', '사무자동화체계', '3중대 1반 업무용 노트북 21', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 1반 사무실'), NULL, 'Rev.A', '2022-09-03', 'Windows 11 Pro', '23H2', '2023-07-25', 'DEFENSE', '2022-09-03', '2022년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1072, '레이저 프린터 6호', 19, 'PR-M400', 'PRM-23-00006', '사무자동화체계', '3중대 1반 레이저 프린터 6', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 1반 사무실'), '전원 불량, 수리 의뢰 예정', 'Rev.A', '2023-09-06', '펌웨어', '3.2.1', '2024-09-21', 'DEFENSE', '2023-09-06', '2023년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'BROKEN', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1073, '휴대용 무전기 22호', 19, 'RT-200', 'R2-21-00022', '전술무전체계', '3중대 1반 휴대용 무전기 22', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 통신창고'), NULL, 'HW 2.1', '2021-01-29', '펌웨어', '2.5.1', '2023-03-23', 'BATTLEFIELD', '2021-01-29', '2021년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1074, '차량용 무전기 8호', 19, 'VR-50', 'V5-19-00008', '전술무전체계', '3중대 1반 차량용 무전기 8', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 통신창고'), '예비품으로 보관', 'Rev.A', '2019-09-26', '펌웨어', '1.9.3', '2020-10-16', 'BATTLEFIELD', '2019-09-26', '2019년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1075, '소형 발전기 8호', 19, 'GEN-2K', 'G2-18-00020', '전원체계', '3중대 1반 소형 발전기 8', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 통신창고'), '화면 출력 안 됨', 'Rev.B', '2018-04-12', NULL, NULL, NULL, NULL, '2018-04-12', '2018년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'BROKEN', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1076, '전술 태블릿 5호', 19, 'TB-10', 'TB1-23-00005', '전장정보 단말체계', '3중대 1반 전술 태블릿 5', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 통신창고'), NULL, '메인보드 1.3', '2023-07-12', 'Android', '13', '2024-11-21', 'BATTLEFIELD', '2023-07-12', '2023년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1077, '문서 작업용 단독 PC 7호', 19, 'DT-5200', 'DT5-15-00007', '사무자동화체계', '3중대 1반 문서 작업용 단독 PC 7', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 1반 사무실'), NULL, '메인보드 1.3', '2015-02-02', 'Windows 10 Pro', '22H2', '2016-12-25', 'STANDALONE', '2015-02-02', '2015년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1078, '행정용 PC 14호', 20, 'DT-7400', 'DT7-15-00014', '사무자동화체계', '3중대 2반 행정용 PC 14', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 2반 사무실'), '모니터 2대 연결', 'Rev.B', '2015-12-22', 'Windows 11 Pro', '24H2', '2018-03-30', 'DEFENSE', '2015-12-22', '2015년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1079, '업무용 노트북 22호', 20, 'NB-15', 'NB5-22-00022', '사무자동화체계', '3중대 2반 업무용 노트북 22', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 2반 사무실'), NULL, '메인보드 1.3', '2022-03-14', 'Windows 11 Pro', '23H2', '2022-06-07', 'DEFENSE', '2022-03-14', '2022년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1080, '휴대용 무전기 23호', 20, 'RT-200', 'R2-25-00023', '전술무전체계', '3중대 2반 휴대용 무전기 23', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 통신창고'), NULL, '메인보드 1.3', '2025-04-22', '펌웨어', '2.4.0', '2025-06-11', 'BATTLEFIELD', '2025-04-22', '2025년 전술통신 장비 도입 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1081, '휴대용 무전기 24호', 20, 'RT-200', 'R2-25-00024', '전술무전체계', '3중대 2반 휴대용 무전기 24', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 통신창고'), NULL, 'Rev.B', '2025-11-09', '펌웨어', '2.5.1', '2026-06-04', 'BATTLEFIELD', '2025-11-09', '2025년 전술통신 장비 도입 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1082, '차량용 무전기 9호', 20, 'VR-50', 'V5-23-00009', '전술무전체계', '3중대 2반 차량용 무전기 9', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 통신창고'), NULL, '메인보드 1.3', '2023-04-11', '펌웨어', '1.9.3', '2024-01-24', 'BATTLEFIELD', '2023-04-11', '2023년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1083, '소형 발전기 9호', 20, 'GEN-2K', 'G2-24-00021', '전원체계', '3중대 2반 소형 발전기 9', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 통신창고'), NULL, 'HW 1.0', '2024-04-14', NULL, NULL, NULL, NULL, '2024-04-14', '2024년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1084, '전술 태블릿 6호', 20, 'TB-10', 'TB1-20-00006', '전장정보 단말체계', '3중대 2반 전술 태블릿 6', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 통신창고'), NULL, 'Rev.C', '2020-01-16', 'Android', '14', '2021-12-24', 'BATTLEFIELD', '2020-01-16', '2020년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1085, '복합기 1호', 2, 'MF-6500', 'MF6-22-00001', '사무자동화체계', '1중대 복합기 1', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 행정반'), '케이블 정리 완료', 'Rev.A', '2022-10-27', '펌웨어', '5.0.4', '2024-05-15', 'DEFENSE', '2022-10-27', '2022년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1086, '영상회의 단말 1호', 2, 'VC-300', 'VC3-22-00001', '영상회의체계', '1중대 영상회의 단말 1', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 행정반'), NULL, 'Rev.B', '2022-04-18', '펌웨어', '8.1.0', '2023-08-07', 'DEFENSE', '2022-04-18', '2022년 영상회의 체계 구축 사업', '2026년 영상회의 체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1087, '행정용 PC 15호', 2, 'DT-7400', 'DT7-16-00015', '사무자동화체계', '1중대 행정용 PC 15', (SELECT l.id FROM location l WHERE l.active_name = '1대대 1중대 행정반'), NULL, 'Rev.A', '2016-03-13', 'Windows 11 Pro', '24H2', '2016-03-15', 'DEFENSE', '2016-03-13', '2016년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1088, '복합기 2호', 3, 'MF-6500', 'MF6-21-00002', '사무자동화체계', '2중대 복합기 2', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 행정반'), '화면 출력 안 됨', 'Rev.C', '2021-03-17', '펌웨어', '5.0.4', '2021-04-13', 'DEFENSE', '2021-03-17', '2021년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'BROKEN', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1089, '영상회의 단말 2호', 3, 'VC-300', 'VC3-15-00002', '영상회의체계', '2중대 영상회의 단말 2', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 행정반'), NULL, 'HW 2.1', '2015-04-10', '펌웨어', '8.1.0', '2015-05-09', 'DEFENSE', '2015-04-10', '2015년 영상회의 체계 구축 사업', NULL, 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1090, '행정용 PC 16호', 3, 'DT-7400', 'DT7-20-00016', '사무자동화체계', '2중대 행정용 PC 16', (SELECT l.id FROM location l WHERE l.active_name = '1대대 2중대 행정반'), '정기 정비 입고', 'Rev.A', '2020-06-17', 'Windows 11 Pro', '24H2', '2022-01-04', 'DEFENSE', '2020-06-17', '2020년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'MAINTENANCE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1091, '복합기 3호', 4, 'MF-6500', 'MF6-15-00003', '사무자동화체계', '3중대 복합기 3', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 행정반'), NULL, 'HW 1.0', '2015-08-06', '펌웨어', '5.0.4', '2016-05-30', 'DEFENSE', '2015-08-06', '2015년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1092, '영상회의 단말 3호', 4, 'VC-300', 'VC3-16-00003', '영상회의체계', '3중대 영상회의 단말 3', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 행정반'), '배터리 교체 필요', 'Rev.A', '2016-12-31', '펌웨어', '8.1.0', '2018-12-07', 'DEFENSE', '2016-12-31', '2016년 영상회의 체계 구축 사업', '2026년 영상회의 체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1093, '행정용 PC 17호', 4, 'DT-7400', 'DT7-18-00017', '사무자동화체계', '3중대 행정용 PC 17', (SELECT l.id FROM location l WHERE l.active_name = '1대대 3중대 행정반'), NULL, 'HW 1.0', '2018-03-27', 'Windows 11 Pro', '24H2', '2020-04-23', 'DEFENSE', '2018-03-27', '2018년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1094, '복합기 4호', 12, 'MF-6500', 'MF6-18-00004', '사무자동화체계', '1중대 복합기 4', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 행정반'), NULL, 'Rev.C', '2018-04-25', '펌웨어', '5.0.4', '2019-12-23', 'DEFENSE', '2018-04-25', '2018년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1095, '영상회의 단말 4호', 12, 'VC-300', 'VC3-22-00004', '영상회의체계', '1중대 영상회의 단말 4', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 행정반'), NULL, 'HW 2.1', '2022-07-18', '펌웨어', '8.1.0', '2023-12-16', 'DEFENSE', '2022-07-18', '2022년 영상회의 체계 구축 사업', NULL, 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1096, '행정용 PC 18호', 12, 'DT-7400', 'DT7-23-00018', '사무자동화체계', '1중대 행정용 PC 18', (SELECT l.id FROM location l WHERE l.active_name = '2대대 1중대 행정반'), '내용연수 경과로 불용', '메인보드 1.3', '2023-11-27', 'Windows 11 Pro', '24H2', '2025-05-30', 'DEFENSE', '2023-11-27', '2023년 사무자동화 장비 보강 사업', NULL, 'DISPOSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1097, '복합기 5호', 13, 'MF-6500', 'MF6-24-00005', '사무자동화체계', '2중대 복합기 5', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 행정반'), NULL, 'Rev.A', '2024-12-25', '펌웨어', '5.0.4', '2026-05-11', 'DEFENSE', '2024-12-25', '2024년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1098, '영상회의 단말 5호', 13, 'VC-300', 'VC3-19-00005', '영상회의체계', '2중대 영상회의 단말 5', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 행정반'), NULL, 'Rev.C', '2019-10-11', '펌웨어', '8.1.0', '2021-08-31', 'DEFENSE', '2019-10-11', '2019년 영상회의 체계 구축 사업', '2026년 영상회의 체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1099, '행정용 PC 19호', 13, 'DT-7400', 'DT7-19-00019', '사무자동화체계', '2중대 행정용 PC 19', (SELECT l.id FROM location l WHERE l.active_name = '2대대 2중대 행정반'), NULL, 'HW 1.0', '2019-12-16', 'Windows 11 Pro', '24H2', '2020-01-24', 'DEFENSE', '2019-12-16', '2019년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1100, '복합기 6호', 14, 'MF-6500', 'MF6-17-00006', '사무자동화체계', '3중대 복합기 6', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 행정반'), NULL, 'Rev.B', '2017-02-09', '펌웨어', '5.0.4', '2018-12-21', 'DEFENSE', '2017-02-09', '2017년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1101, '영상회의 단말 6호', 14, 'VC-300', 'VC3-23-00006', '영상회의체계', '3중대 영상회의 단말 6', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 행정반'), NULL, 'Rev.B', '2023-04-22', '펌웨어', '8.1.0', '2024-11-29', 'DEFENSE', '2023-04-22', '2023년 영상회의 체계 구축 사업', '2026년 영상회의 체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1102, '행정용 PC 20호', 14, 'DT-7400', 'DT7-19-00020', '사무자동화체계', '3중대 행정용 PC 20', (SELECT l.id FROM location l WHERE l.active_name = '2대대 3중대 행정반'), '예비품으로 보관', 'Rev.C', '2019-12-29', 'Windows 11 Pro', '24H2', '2021-09-17', 'DEFENSE', '2019-12-29', '2019년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1103, '업무 서버 1호', 1, 'SV-R520', 'SVR-22-00001', '부대 네트워크 기반체계', '1대대 업무 서버 1', (SELECT l.id FROM location l WHERE l.active_name = '1대대 전산실'), '예비품으로 보관', '메인보드 1.3', '2022-05-18', 'Rocky Linux', '9.4', '2022-12-24', 'DEFENSE', '2022-05-18', '2022년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1104, '업무 서버 2호', 1, 'SV-R520', 'SVR-21-00002', '부대 네트워크 기반체계', '1대대 업무 서버 2', (SELECT l.id FROM location l WHERE l.active_name = '1대대 전산실'), '배터리 교체 필요', 'Rev.C', '2021-07-10', 'Rocky Linux', '9.4', '2023-02-21', 'DEFENSE', '2021-07-10', '2021년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1105, '라우터 1호', 1, 'GR-3000', 'GR3-17-00001', '부대 네트워크 기반체계', '1대대 라우터 1', (SELECT l.id FROM location l WHERE l.active_name = '1대대 통신장비실'), NULL, 'Rev.A', '2017-10-15', 'NOS', '15.2.7', '2017-10-26', 'DEFENSE', '2017-10-15', '2017년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1106, '방화벽 1호', 1, 'FW-500', 'FW5-16-00001', '부대 네트워크 기반체계', '1대대 방화벽 1', (SELECT l.id FROM location l WHERE l.active_name = '1대대 통신장비실'), '모니터 2대 연결', 'Rev.B', '2016-12-09', '보안 OS', '7.0.15', '2017-06-11', 'DEFENSE', '2016-12-09', '2016년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1107, '저장장치(NAS) 1호', 1, 'NAS-8B', 'NS8-23-00001', '부대 네트워크 기반체계', '1대대 저장장치(NAS) 1', (SELECT l.id FROM location l WHERE l.active_name = '1대대 전산실'), NULL, 'Rev.A', '2023-07-02', 'NAS OS', '7.2.2', '2023-12-18', 'DEFENSE', '2023-07-02', '2023년 부대 네트워크 고도화 사업', NULL, 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1108, 'CCTV 녹화기 1호', 1, 'NVR-16', 'NV16-19-00001', '영상감시체계', '1대대 CCTV 녹화기 1', (SELECT l.id FROM location l WHERE l.active_name = '1대대 지휘통제실'), NULL, 'Rev.A', '2019-11-13', '펌웨어', '4.30.2', '2020-03-03', 'ISOLATED', '2019-11-13', '2019년 영상감시 체계 구축 사업', '2026년 영상감시 장비 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1109, '영상회의 서버 1호', 1, 'VC-MCU-20', 'MCU-22-00001', '영상회의체계', '1대대 영상회의 서버 1', (SELECT l.id FROM location l WHERE l.active_name = '1대대 지휘통제실'), NULL, '메인보드 1.3', '2022-06-27', 'Linux', '5.15', '2023-03-17', 'DEFENSE', '2022-06-27', '2022년 영상회의 체계 구축 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1110, '업무 서버 3호', 11, 'SV-R520', 'SVR-16-00003', '부대 네트워크 기반체계', '2대대 업무 서버 3', (SELECT l.id FROM location l WHERE l.active_name = '2대대 전산실'), NULL, 'HW 2.1', '2016-02-06', 'Rocky Linux', '9.4', '2018-01-12', 'DEFENSE', '2016-02-06', '2016년 부대 네트워크 고도화 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1111, '업무 서버 4호', 11, 'SV-R520', 'SVR-15-00004', '부대 네트워크 기반체계', '2대대 업무 서버 4', (SELECT l.id FROM location l WHERE l.active_name = '2대대 전산실'), NULL, 'HW 1.0', '2015-02-10', 'Rocky Linux', '9.4', '2015-05-14', 'DEFENSE', '2015-02-10', '2015년 부대 네트워크 고도화 사업', NULL, 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1112, '라우터 2호', 11, 'GR-3000', 'GR3-24-00002', '부대 네트워크 기반체계', '2대대 라우터 2', (SELECT l.id FROM location l WHERE l.active_name = '2대대 통신장비실'), '내용연수 경과로 불용', 'HW 2.1', '2024-01-13', 'NOS', '15.2.7', '2025-01-16', 'DEFENSE', '2024-01-13', '2024년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'DISPOSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1113, '방화벽 2호', 11, 'FW-500', 'FW5-16-00002', '부대 네트워크 기반체계', '2대대 방화벽 2', (SELECT l.id FROM location l WHERE l.active_name = '2대대 통신장비실'), '내용연수 경과, 불용 심의 대기', 'HW 1.0', '2016-03-23', '보안 OS', '7.0.15', '2018-04-27', 'DEFENSE', '2016-03-23', '2016년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'DISPOSAL_PENDING', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1114, '저장장치(NAS) 2호', 11, 'NAS-8B', 'NS8-22-00002', '부대 네트워크 기반체계', '2대대 저장장치(NAS) 2', (SELECT l.id FROM location l WHERE l.active_name = '2대대 전산실'), NULL, 'Rev.C', '2022-10-31', 'NAS OS', '7.2.2', '2022-12-23', 'DEFENSE', '2022-10-31', '2022년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1115, 'CCTV 녹화기 2호', 11, 'NVR-16', 'NV16-15-00002', '영상감시체계', '2대대 CCTV 녹화기 2', (SELECT l.id FROM location l WHERE l.active_name = '2대대 지휘통제실'), NULL, 'Rev.B', '2015-01-20', '펌웨어', '4.30.2', '2015-08-26', 'ISOLATED', '2015-01-20', '2015년 영상감시 체계 구축 사업', '2026년 영상감시 장비 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1116, '영상회의 서버 2호', 11, 'VC-MCU-20', 'MCU-20-00002', '영상회의체계', '2대대 영상회의 서버 2', (SELECT l.id FROM location l WHERE l.active_name = '2대대 지휘통제실'), '내용연수 경과, 불용 심의 대기', '메인보드 1.3', '2020-10-22', 'Linux', '5.15', '2022-02-06', 'DEFENSE', '2020-10-22', '2020년 영상회의 체계 구축 사업', '2026년 영상회의 체계 유지보수', 'DISPOSAL_PENDING', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-02 12:00:00') - INTERVAL 7 DAY
) AS v
WHERE NOT EXISTS (SELECT 1 FROM equipment o WHERE o.id = v.id)
  AND NOT EXISTS (SELECT 1 FROM equipment o WHERE o.status <> 'DISPOSED' AND v.status <> 'DISPOSED' AND o.model_name = v.model_name AND o.serial_number = v.serial_number);

-- SW·네트워크

INSERT INTO equipment_software (equipment_id, sort_order, name, version, installed_on)
SELECT v.column_0, v.column_1, v.column_2, v.column_3, v.column_4 FROM (VALUES
    ROW(3, 0, '오피스 2024', '1.2.0', '2026-09-04', 'NB-14', 'SN-NB-0001', '사무자동화체계'),
    ROW(3, 1, '백신 에이전트', '5.0.12', '2026-07-29', 'NB-14', 'SN-NB-0001', '사무자동화체계'),
    ROW(6, 0, '오피스 2024', '1.2.0', '2017-08-20', 'NB-14', 'SN-NB-0002', '사무자동화체계'),
    ROW(6, 1, '백신 에이전트', '5.0.12', '2017-08-02', 'NB-14', 'SN-NB-0002', '사무자동화체계'),
    ROW(9, 0, '오피스 2024', '1.2.0', '2019-01-31', 'NB-14', 'SN-NB-0003', '사무자동화체계'),
    ROW(9, 1, '백신 에이전트', '5.0.12', '2018-12-29', 'NB-14', 'SN-NB-0003', '사무자동화체계'),
    ROW(12, 0, '오피스 2024', '1.2.0', '2018-08-15', 'NB-14', 'SN-NB-0004', '사무자동화체계'),
    ROW(12, 1, '백신 에이전트', '5.0.12', '2018-08-21', 'NB-14', 'SN-NB-0004', '사무자동화체계'),
    ROW(15, 0, '오피스 2024', '1.2.0', '2023-01-03', 'NB-14', 'SN-NB-0005', '사무자동화체계'),
    ROW(15, 1, '백신 에이전트', '5.0.12', '2023-01-13', 'NB-14', 'SN-NB-0005', '사무자동화체계'),
    ROW(15, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2023-01-08', 'NB-14', 'SN-NB-0005', '사무자동화체계'),
    ROW(18, 0, '오피스 2024', '1.2.0', '2019-09-21', 'NB-14', 'SN-NB-0006', '사무자동화체계'),
    ROW(18, 1, '백신 에이전트', '5.0.12', '2019-11-03', 'NB-14', 'SN-NB-0006', '사무자동화체계'),
    ROW(21, 0, '오피스 2024', '1.2.0', '2016-05-27', 'NB-14', 'SN-NB-0007', '사무자동화체계'),
    ROW(21, 1, '백신 에이전트', '5.0.12', '2016-06-29', 'NB-14', 'SN-NB-0007', '사무자동화체계'),
    ROW(24, 0, '오피스 2024', '1.2.0', '2020-01-02', 'NB-14', 'SN-NB-0008', '사무자동화체계'),
    ROW(24, 1, '백신 에이전트', '5.0.12', '2020-01-15', 'NB-14', 'SN-NB-0008', '사무자동화체계'),
    ROW(27, 0, '오피스 2024', '1.2.0', '2026-09-30', 'NB-14', 'SN-NB-0009', '사무자동화체계'),
    ROW(27, 1, '백신 에이전트', '5.0.12', '2026-10-15', 'NB-14', 'SN-NB-0009', '사무자동화체계'),
    ROW(27, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2026-11-01', 'NB-14', 'SN-NB-0009', '사무자동화체계'),
    ROW(30, 0, '오피스 2024', '1.2.0', '2019-12-23', 'NB-14', 'SN-NB-0010', '사무자동화체계'),
    ROW(30, 1, '백신 에이전트', '5.0.12', '2019-12-11', 'NB-14', 'SN-NB-0010', '사무자동화체계'),
    ROW(33, 0, '오피스 2024', '1.2.0', '2018-09-28', 'NB-14', 'SN-NB-0011', '사무자동화체계'),
    ROW(33, 1, '백신 에이전트', '5.0.12', '2018-10-07', 'NB-14', 'SN-NB-0011', '사무자동화체계'),
    ROW(36, 0, '오피스 2024', '1.2.0', '2026-11-02', 'NB-14', 'SN-NB-0012', '사무자동화체계'),
    ROW(36, 1, '백신 에이전트', '5.0.12', '2026-11-09', 'NB-14', 'SN-NB-0012', '사무자동화체계'),
    ROW(1001, 0, '오피스 2024', '1.2.0', '2021-10-09', 'DT-7400', 'DT7-21-00001', '사무자동화체계'),
    ROW(1001, 1, '백신 에이전트', '5.0.12', '2021-08-17', 'DT-7400', 'DT7-21-00001', '사무자동화체계'),
    ROW(1001, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2021-10-03', 'DT-7400', 'DT7-21-00001', '사무자동화체계'),
    ROW(1001, 3, 'PDF 뷰어', '11.2', '2021-08-27', 'DT-7400', 'DT7-21-00001', '사무자동화체계'),
    ROW(1002, 0, '오피스 2024', '1.2.0', '2025-03-31', 'DT-7400', 'DT7-23-00002', '사무자동화체계'),
    ROW(1002, 1, '백신 에이전트', '5.0.12', '2025-03-30', 'DT-7400', 'DT7-23-00002', '사무자동화체계'),
    ROW(1002, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2025-03-01', 'DT-7400', 'DT7-23-00002', '사무자동화체계'),
    ROW(1003, 0, '오피스 2024', '1.2.0', '2024-04-30', 'NB-15', 'NB5-23-00013', '사무자동화체계'),
    ROW(1003, 1, '백신 에이전트', '5.0.12', '2024-04-19', 'NB-15', 'NB5-23-00013', '사무자동화체계'),
    ROW(1003, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2024-04-20', 'NB-15', 'NB5-23-00013', '사무자동화체계'),
    ROW(1008, 0, '오피스 2024', '1.2.0', '2017-11-12', 'DT-7400', 'DT7-15-00003', '사무자동화체계'),
    ROW(1008, 1, '백신 에이전트', '5.0.12', '2017-10-03', 'DT-7400', 'DT7-15-00003', '사무자동화체계'),
    ROW(1008, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2017-11-02', 'DT-7400', 'DT7-15-00003', '사무자동화체계'),
    ROW(1009, 0, '오피스 2024', '1.2.0', '2026-01-05', 'NB-15', 'NB5-24-00014', '사무자동화체계'),
    ROW(1009, 1, '백신 에이전트', '5.0.12', '2026-01-29', 'NB-15', 'NB5-24-00014', '사무자동화체계'),
    ROW(1009, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2026-01-28', 'NB-15', 'NB5-24-00014', '사무자동화체계'),
    ROW(1020, 0, '지도 열람 앱', '4.1.0', '2017-08-23', 'TB-10', 'TB1-15-00001', '전장정보 단말체계'),
    ROW(1020, 1, '보안 메신저', '2.3.5', '2017-07-29', 'TB-10', 'TB1-15-00001', '전장정보 단말체계'),
    ROW(1021, 0, '오피스 2024', '1.2.0', '2019-11-27', 'DT-5200', 'DT5-18-00001', '사무자동화체계'),
    ROW(1021, 1, '백신 에이전트', '5.0.12', '2019-11-18', 'DT-5200', 'DT5-18-00001', '사무자동화체계'),
    ROW(1022, 0, '오피스 2024', '1.2.0', '2017-12-30', 'DT-7400', 'DT7-15-00004', '사무자동화체계'),
    ROW(1022, 1, '백신 에이전트', '5.0.12', '2018-01-04', 'DT-7400', 'DT7-15-00004', '사무자동화체계'),
    ROW(1022, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2017-12-08', 'DT-7400', 'DT7-15-00004', '사무자동화체계'),
    ROW(1022, 3, 'PDF 뷰어', '11.2', '2017-12-09', 'DT-7400', 'DT7-15-00004', '사무자동화체계'),
    ROW(1022, 4, '원격 관리 에이전트', '2.8.0', '2018-01-02', 'DT-7400', 'DT7-15-00004', '사무자동화체계'),
    ROW(1023, 0, '오피스 2024', '1.2.0', '2022-08-29', 'DT-7400', 'DT7-21-00005', '사무자동화체계'),
    ROW(1023, 1, '백신 에이전트', '5.0.12', '2022-09-24', 'DT-7400', 'DT7-21-00005', '사무자동화체계'),
    ROW(1023, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2022-08-30', 'DT-7400', 'DT7-21-00005', '사무자동화체계'),
    ROW(1023, 3, 'PDF 뷰어', '11.2', '2022-08-25', 'DT-7400', 'DT7-21-00005', '사무자동화체계'),
    ROW(1023, 4, '원격 관리 에이전트', '2.8.0', '2022-08-19', 'DT-7400', 'DT7-21-00005', '사무자동화체계'),
    ROW(1024, 0, '오피스 2024', '1.2.0', '2024-09-02', 'NB-15', 'NB5-22-00015', '사무자동화체계'),
    ROW(1024, 1, '백신 에이전트', '5.0.12', '2024-07-30', 'NB-15', 'NB5-22-00015', '사무자동화체계'),
    ROW(1024, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2024-07-31', 'NB-15', 'NB5-22-00015', '사무자동화체계'),
    ROW(1028, 0, '오피스 2024', '1.2.0', '2017-07-21', 'DT-5200', 'DT5-16-00002', '사무자동화체계'),
    ROW(1028, 1, '백신 에이전트', '5.0.12', '2017-07-28', 'DT-5200', 'DT5-16-00002', '사무자동화체계'),
    ROW(1028, 2, '보안 USB 관리 프로그램', '1.6.3', '2017-08-31', 'DT-5200', 'DT5-16-00002', '사무자동화체계'),
    ROW(1029, 0, '오피스 2024', '1.2.0', '2022-07-15', 'DT-7400', 'DT7-21-00006', '사무자동화체계'),
    ROW(1029, 1, '백신 에이전트', '5.0.12', '2022-05-24', 'DT-7400', 'DT7-21-00006', '사무자동화체계'),
    ROW(1030, 0, '오피스 2024', '1.2.0', '2019-02-15', 'DT-7400', 'DT7-16-00007', '사무자동화체계'),
    ROW(1030, 1, '백신 에이전트', '5.0.12', '2019-01-27', 'DT-7400', 'DT7-16-00007', '사무자동화체계'),
    ROW(1030, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2019-01-03', 'DT-7400', 'DT7-16-00007', '사무자동화체계'),
    ROW(1030, 3, 'PDF 뷰어', '11.2', '2019-01-29', 'DT-7400', 'DT7-16-00007', '사무자동화체계'),
    ROW(1030, 4, '원격 관리 에이전트', '2.8.0', '2019-01-11', 'DT-7400', 'DT7-16-00007', '사무자동화체계'),
    ROW(1031, 0, '오피스 2024', '1.2.0', '2026-03-05', 'NB-15', 'NB5-25-00016', '사무자동화체계'),
    ROW(1031, 1, '백신 에이전트', '5.0.12', '2026-01-28', 'NB-15', 'NB5-25-00016', '사무자동화체계'),
    ROW(1036, 0, '오피스 2024', '1.2.0', '2026-11-08', 'DT-7400', 'DT7-25-00008', '사무자동화체계'),
    ROW(1036, 1, '백신 에이전트', '5.0.12', '2026-10-05', 'DT-7400', 'DT7-25-00008', '사무자동화체계'),
    ROW(1036, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2026-10-08', 'DT-7400', 'DT7-25-00008', '사무자동화체계'),
    ROW(1036, 3, 'PDF 뷰어', '11.2', '2026-10-20', 'DT-7400', 'DT7-25-00008', '사무자동화체계'),
    ROW(1037, 0, '오피스 2024', '1.2.0', '2017-12-21', 'DT-7400', 'DT7-16-00009', '사무자동화체계'),
    ROW(1037, 1, '백신 에이전트', '5.0.12', '2018-02-13', 'DT-7400', 'DT7-16-00009', '사무자동화체계'),
    ROW(1037, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2017-12-19', 'DT-7400', 'DT7-16-00009', '사무자동화체계'),
    ROW(1037, 3, 'PDF 뷰어', '11.2', '2017-12-25', 'DT-7400', 'DT7-16-00009', '사무자동화체계'),
    ROW(1038, 0, '오피스 2024', '1.2.0', '2021-08-11', 'NB-15', 'NB5-20-00017', '사무자동화체계'),
    ROW(1038, 1, '백신 에이전트', '5.0.12', '2021-08-03', 'NB-15', 'NB5-20-00017', '사무자동화체계'),
    ROW(1038, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2021-08-16', 'NB-15', 'NB5-20-00017', '사무자동화체계'),
    ROW(1042, 0, '지도 열람 앱', '4.1.0', '2020-01-20', 'TB-10', 'TB1-18-00002', '전장정보 단말체계'),
    ROW(1042, 1, '보안 메신저', '2.3.5', '2020-02-11', 'TB-10', 'TB1-18-00002', '전장정보 단말체계'),
    ROW(1043, 0, '오피스 2024', '1.2.0', '2021-08-18', 'NB-15', 'NB5-19-00018', '사무자동화체계'),
    ROW(1043, 1, '백신 에이전트', '5.0.12', '2021-09-13', 'NB-15', 'NB5-19-00018', '사무자동화체계'),
    ROW(1049, 0, '오피스 2024', '1.2.0', '2017-01-13', 'DT-5200', 'DT5-15-00003', '사무자동화체계'),
    ROW(1049, 1, '백신 에이전트', '5.0.12', '2017-02-02', 'DT-5200', 'DT5-15-00003', '사무자동화체계'),
    ROW(1050, 0, '오피스 2024', '1.2.0', '2023-02-19', 'DT-7400', 'DT7-22-00010', '사무자동화체계'),
    ROW(1050, 1, '백신 에이전트', '5.0.12', '2023-02-28', 'DT-7400', 'DT7-22-00010', '사무자동화체계'),
    ROW(1050, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2023-01-11', 'DT-7400', 'DT7-22-00010', '사무자동화체계'),
    ROW(1050, 3, 'PDF 뷰어', '11.2', '2023-02-21', 'DT-7400', 'DT7-22-00010', '사무자동화체계'),
    ROW(1050, 4, '원격 관리 에이전트', '2.8.0', '2023-02-08', 'DT-7400', 'DT7-22-00010', '사무자동화체계'),
    ROW(1051, 0, '오피스 2024', '1.2.0', '2021-07-05', 'DT-7400', 'DT7-20-00011', '사무자동화체계'),
    ROW(1051, 1, '백신 에이전트', '5.0.12', '2021-07-24', 'DT-7400', 'DT7-20-00011', '사무자동화체계'),
    ROW(1051, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2021-08-15', 'DT-7400', 'DT7-20-00011', '사무자동화체계'),
    ROW(1052, 0, '오피스 2024', '1.2.0', '2020-11-10', 'NB-15', 'NB5-20-00019', '사무자동화체계'),
    ROW(1052, 1, '백신 에이전트', '5.0.12', '2020-09-22', 'NB-15', 'NB5-20-00019', '사무자동화체계'),
    ROW(1055, 0, '지도 열람 앱', '4.1.0', '2024-02-22', 'TB-10', 'TB1-21-00003', '전장정보 단말체계'),
    ROW(1055, 1, '보안 메신저', '2.3.5', '2024-02-17', 'TB-10', 'TB1-21-00003', '전장정보 단말체계'),
    ROW(1056, 0, '오피스 2024', '1.2.0', '2017-07-12', 'DT-5200', 'DT5-17-00004', '사무자동화체계'),
    ROW(1056, 1, '백신 에이전트', '5.0.12', '2017-07-24', 'DT-5200', 'DT5-17-00004', '사무자동화체계'),
    ROW(1057, 0, '오피스 2024', '1.2.0', '2023-09-25', 'DT-7400', 'DT7-22-00012', '사무자동화체계'),
    ROW(1057, 1, '백신 에이전트', '5.0.12', '2023-11-04', 'DT-7400', 'DT7-22-00012', '사무자동화체계'),
    ROW(1058, 0, '오피스 2024', '1.2.0', '2026-06-09', 'NB-15', 'NB5-24-00020', '사무자동화체계'),
    ROW(1058, 1, '백신 에이전트', '5.0.12', '2026-06-19', 'NB-15', 'NB5-24-00020', '사무자동화체계'),
    ROW(1058, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2026-05-24', 'NB-15', 'NB5-24-00020', '사무자동화체계'),
    ROW(1063, 0, '오피스 2024', '1.2.0', '2018-07-15', 'DT-5200', 'DT5-18-00005', '사무자동화체계'),
    ROW(1063, 1, '백신 에이전트', '5.0.12', '2018-05-25', 'DT-5200', 'DT5-18-00005', '사무자동화체계'),
    ROW(1064, 0, '오피스 2024', '1.2.0', '2023-08-28', 'DT-7400', 'DT7-21-00013', '사무자동화체계'),
    ROW(1064, 1, '백신 에이전트', '5.0.12', '2023-10-03', 'DT-7400', 'DT7-21-00013', '사무자동화체계'),
    ROW(1064, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2023-09-10', 'DT-7400', 'DT7-21-00013', '사무자동화체계'),
    ROW(1069, 0, '지도 열람 앱', '4.1.0', '2020-01-03', 'TB-10', 'TB1-17-00004', '전장정보 단말체계'),
    ROW(1069, 1, '보안 메신저', '2.3.5', '2019-12-26', 'TB-10', 'TB1-17-00004', '전장정보 단말체계'),
    ROW(1070, 0, '오피스 2024', '1.2.0', '2020-11-17', 'DT-5200', 'DT5-19-00006', '사무자동화체계'),
    ROW(1070, 1, '백신 에이전트', '5.0.12', '2020-10-09', 'DT-5200', 'DT5-19-00006', '사무자동화체계'),
    ROW(1070, 2, '보안 USB 관리 프로그램', '1.6.3', '2020-10-31', 'DT-5200', 'DT5-19-00006', '사무자동화체계'),
    ROW(1071, 0, '오피스 2024', '1.2.0', '2023-09-13', 'NB-15', 'NB5-22-00021', '사무자동화체계'),
    ROW(1071, 1, '백신 에이전트', '5.0.12', '2023-09-13', 'NB-15', 'NB5-22-00021', '사무자동화체계'),
    ROW(1071, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2023-09-19', 'NB-15', 'NB5-22-00021', '사무자동화체계'),
    ROW(1076, 0, '지도 열람 앱', '4.1.0', '2024-12-18', 'TB-10', 'TB1-23-00005', '전장정보 단말체계'),
    ROW(1076, 1, '보안 메신저', '2.3.5', '2025-01-12', 'TB-10', 'TB1-23-00005', '전장정보 단말체계'),
    ROW(1077, 0, '오피스 2024', '1.2.0', '2017-01-10', 'DT-5200', 'DT5-15-00007', '사무자동화체계'),
    ROW(1077, 1, '백신 에이전트', '5.0.12', '2017-02-20', 'DT-5200', 'DT5-15-00007', '사무자동화체계'),
    ROW(1078, 0, '오피스 2024', '1.2.0', '2018-04-24', 'DT-7400', 'DT7-15-00014', '사무자동화체계'),
    ROW(1078, 1, '백신 에이전트', '5.0.12', '2018-04-30', 'DT-7400', 'DT7-15-00014', '사무자동화체계'),
    ROW(1078, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2018-04-23', 'DT-7400', 'DT7-15-00014', '사무자동화체계'),
    ROW(1079, 0, '오피스 2024', '1.2.0', '2022-07-08', 'NB-15', 'NB5-22-00022', '사무자동화체계'),
    ROW(1079, 1, '백신 에이전트', '5.0.12', '2022-06-10', 'NB-15', 'NB5-22-00022', '사무자동화체계'),
    ROW(1079, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2022-06-25', 'NB-15', 'NB5-22-00022', '사무자동화체계'),
    ROW(1084, 0, '지도 열람 앱', '4.1.0', '2022-01-07', 'TB-10', 'TB1-20-00006', '전장정보 단말체계'),
    ROW(1084, 1, '보안 메신저', '2.3.5', '2022-02-07', 'TB-10', 'TB1-20-00006', '전장정보 단말체계'),
    ROW(1086, 0, '영상회의 클라이언트', '8.1.0', '2023-08-29', 'VC-300', 'VC3-22-00001', '영상회의체계'),
    ROW(1087, 0, '오피스 2024', '1.2.0', '2016-03-17', 'DT-7400', 'DT7-16-00015', '사무자동화체계'),
    ROW(1087, 1, '백신 에이전트', '5.0.12', '2016-05-05', 'DT-7400', 'DT7-16-00015', '사무자동화체계'),
    ROW(1087, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2016-05-12', 'DT-7400', 'DT7-16-00015', '사무자동화체계'),
    ROW(1089, 0, '영상회의 클라이언트', '8.1.0', '2015-05-27', 'VC-300', 'VC3-15-00002', '영상회의체계'),
    ROW(1090, 0, '오피스 2024', '1.2.0', '2022-01-14', 'DT-7400', 'DT7-20-00016', '사무자동화체계'),
    ROW(1090, 1, '백신 에이전트', '5.0.12', '2022-02-16', 'DT-7400', 'DT7-20-00016', '사무자동화체계'),
    ROW(1090, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2022-02-19', 'DT-7400', 'DT7-20-00016', '사무자동화체계'),
    ROW(1090, 3, 'PDF 뷰어', '11.2', '2022-02-06', 'DT-7400', 'DT7-20-00016', '사무자동화체계'),
    ROW(1092, 0, '영상회의 클라이언트', '8.1.0', '2019-01-02', 'VC-300', 'VC3-16-00003', '영상회의체계'),
    ROW(1093, 0, '오피스 2024', '1.2.0', '2020-05-03', 'DT-7400', 'DT7-18-00017', '사무자동화체계'),
    ROW(1093, 1, '백신 에이전트', '5.0.12', '2020-05-10', 'DT-7400', 'DT7-18-00017', '사무자동화체계'),
    ROW(1093, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2020-05-21', 'DT-7400', 'DT7-18-00017', '사무자동화체계'),
    ROW(1095, 0, '영상회의 클라이언트', '8.1.0', '2024-01-14', 'VC-300', 'VC3-22-00004', '영상회의체계'),
    ROW(1096, 0, '오피스 2024', '1.2.0', '2025-06-16', 'DT-7400', 'DT7-23-00018', '사무자동화체계'),
    ROW(1096, 1, '백신 에이전트', '5.0.12', '2025-07-23', 'DT-7400', 'DT7-23-00018', '사무자동화체계'),
    ROW(1096, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2025-06-07', 'DT-7400', 'DT7-23-00018', '사무자동화체계'),
    ROW(1096, 3, 'PDF 뷰어', '11.2', '2025-06-28', 'DT-7400', 'DT7-23-00018', '사무자동화체계'),
    ROW(1096, 4, '원격 관리 에이전트', '2.8.0', '2025-06-20', 'DT-7400', 'DT7-23-00018', '사무자동화체계'),
    ROW(1098, 0, '영상회의 클라이언트', '8.1.0', '2021-10-03', 'VC-300', 'VC3-19-00005', '영상회의체계'),
    ROW(1099, 0, '오피스 2024', '1.2.0', '2020-03-09', 'DT-7400', 'DT7-19-00019', '사무자동화체계'),
    ROW(1099, 1, '백신 에이전트', '5.0.12', '2020-03-22', 'DT-7400', 'DT7-19-00019', '사무자동화체계'),
    ROW(1099, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2020-03-23', 'DT-7400', 'DT7-19-00019', '사무자동화체계'),
    ROW(1101, 0, '영상회의 클라이언트', '8.1.0', '2024-12-15', 'VC-300', 'VC3-23-00006', '영상회의체계'),
    ROW(1102, 0, '오피스 2024', '1.2.0', '2021-11-03', 'DT-7400', 'DT7-19-00020', '사무자동화체계'),
    ROW(1102, 1, '백신 에이전트', '5.0.12', '2021-11-02', 'DT-7400', 'DT7-19-00020', '사무자동화체계'),
    ROW(1102, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2021-09-28', 'DT-7400', 'DT7-19-00020', '사무자동화체계'),
    ROW(1103, 0, '웹 서버', '2.4.62', '2023-01-29', 'SV-R520', 'SVR-22-00001', '부대 네트워크 기반체계'),
    ROW(1103, 1, 'DB 서버', '8.0.40', '2023-01-05', 'SV-R520', 'SVR-22-00001', '부대 네트워크 기반체계'),
    ROW(1103, 2, '백업 에이전트', '6.2.0', '2023-02-01', 'SV-R520', 'SVR-22-00001', '부대 네트워크 기반체계'),
    ROW(1104, 0, '웹 서버', '2.4.62', '2023-03-28', 'SV-R520', 'SVR-21-00002', '부대 네트워크 기반체계'),
    ROW(1104, 1, 'DB 서버', '8.0.40', '2023-03-25', 'SV-R520', 'SVR-21-00002', '부대 네트워크 기반체계'),
    ROW(1104, 2, '백업 에이전트', '6.2.0', '2023-04-21', 'SV-R520', 'SVR-21-00002', '부대 네트워크 기반체계'),
    ROW(1104, 3, '서버 백신', '4.3.1', '2023-03-25', 'SV-R520', 'SVR-21-00002', '부대 네트워크 기반체계'),
    ROW(1107, 0, '백업 관리', '3.0.1', '2024-01-18', 'NAS-8B', 'NS8-23-00001', '부대 네트워크 기반체계'),
    ROW(1108, 0, '영상 관제 프로그램', '2.9.0', '2020-04-25', 'NVR-16', 'NV16-19-00001', '영상감시체계'),
    ROW(1109, 0, '다자간 회의 관리', '6.4.0', '2023-04-28', 'VC-MCU-20', 'MCU-22-00001', '영상회의체계'),
    ROW(1110, 0, '웹 서버', '2.4.62', '2018-02-20', 'SV-R520', 'SVR-16-00003', '부대 네트워크 기반체계'),
    ROW(1110, 1, 'DB 서버', '8.0.40', '2018-01-22', 'SV-R520', 'SVR-16-00003', '부대 네트워크 기반체계'),
    ROW(1110, 2, '백업 에이전트', '6.2.0', '2018-02-26', 'SV-R520', 'SVR-16-00003', '부대 네트워크 기반체계'),
    ROW(1110, 3, '서버 백신', '4.3.1', '2018-02-14', 'SV-R520', 'SVR-16-00003', '부대 네트워크 기반체계'),
    ROW(1111, 0, '웹 서버', '2.4.62', '2015-06-10', 'SV-R520', 'SVR-15-00004', '부대 네트워크 기반체계'),
    ROW(1111, 1, 'DB 서버', '8.0.40', '2015-06-12', 'SV-R520', 'SVR-15-00004', '부대 네트워크 기반체계'),
    ROW(1111, 2, '백업 에이전트', '6.2.0', '2015-06-22', 'SV-R520', 'SVR-15-00004', '부대 네트워크 기반체계'),
    ROW(1114, 0, '백업 관리', '3.0.1', '2023-01-19', 'NAS-8B', 'NS8-22-00002', '부대 네트워크 기반체계'),
    ROW(1115, 0, '영상 관제 프로그램', '2.9.0', '2015-10-01', 'NVR-16', 'NV16-15-00002', '영상감시체계'),
    ROW(1116, 0, '다자간 회의 관리', '6.4.0', '2022-03-10', 'VC-MCU-20', 'MCU-20-00002', '영상회의체계')) v
JOIN equipment e ON e.id = v.column_0 AND e.model_name = v.column_5 AND e.serial_number = v.column_6 AND e.system_name = v.column_7
WHERE NOT EXISTS (SELECT 1 FROM equipment_software s WHERE s.equipment_id = v.column_0);

INSERT INTO equipment_network (equipment_id, sort_order, name, ip, mac, mac_key, active_mac_key)
SELECT v.column_0, v.column_1, v.column_2, v.column_3, v.column_4, v.column_5, IF(e.status = 'DISPOSED', NULL, v.column_5) FROM (VALUES
    ROW(3, 0, '업무망', '192.0.2.10', '02-00-00-00-10-01', '020000001001', '020000001001', 'NB-14', 'SN-NB-0001', '사무자동화체계', '020000001001'),
    ROW(6, 0, '업무망', '192.0.2.11', '02-00-00-00-10-02', '020000001002', '020000001002', 'NB-14', 'SN-NB-0002', '사무자동화체계', '020000001002'),
    ROW(9, 0, '업무망', '192.0.2.12', '02-00-00-00-10-03', '020000001003', '020000001003', 'NB-14', 'SN-NB-0003', '사무자동화체계', '020000001003'),
    ROW(12, 0, '업무망', '192.0.2.13', '02-00-00-00-10-04', '020000001004', '020000001004', 'NB-14', 'SN-NB-0004', '사무자동화체계', '020000001004'),
    ROW(15, 0, '업무망', '192.0.2.14', '02-00-00-00-10-05', '020000001005', '020000001005', 'NB-14', 'SN-NB-0005', '사무자동화체계', '020000001005'),
    ROW(18, 0, '업무망', '192.0.2.15', '02-00-00-00-10-06', '020000001006', '020000001006', 'NB-14', 'SN-NB-0006', '사무자동화체계', '020000001006'),
    ROW(21, 0, '업무망', '198.51.100.10', '02-00-00-00-10-07', '020000001007', '020000001007', 'NB-14', 'SN-NB-0007', '사무자동화체계', '020000001007'),
    ROW(24, 0, '업무망', '198.51.100.11', '02-00-00-00-10-08', '020000001008', '020000001008', 'NB-14', 'SN-NB-0008', '사무자동화체계', '020000001008'),
    ROW(27, 0, '업무망', '198.51.100.12', '02-00-00-00-10-09', '020000001009', '020000001009', 'NB-14', 'SN-NB-0009', '사무자동화체계', '020000001009'),
    ROW(30, 0, '업무망', '198.51.100.13', '02-00-00-00-10-0A', '02000000100A', '02000000100A', 'NB-14', 'SN-NB-0010', '사무자동화체계', '02000000100A'),
    ROW(33, 0, '업무망', '198.51.100.14', '02-00-00-00-10-0B', '02000000100B', '02000000100B', 'NB-14', 'SN-NB-0011', '사무자동화체계', '02000000100B'),
    ROW(36, 0, '업무망', '198.51.100.15', '02-00-00-00-10-0C', '02000000100C', '02000000100C', 'NB-14', 'SN-NB-0012', '사무자동화체계', '02000000100C'),
    ROW(1001, 0, '업무망', '192.0.2.16', '02-00-00-00-10-0D', '02000000100D', '02000000100D', 'DT-7400', 'DT7-21-00001', '사무자동화체계', '02000000100D'),
    ROW(1002, 0, '업무망', '192.0.2.17', '02-00-00-00-10-0E', '02000000100E', '02000000100E', 'DT-7400', 'DT7-23-00002', '사무자동화체계', '02000000100E'),
    ROW(1003, 0, '업무망', '192.0.2.18', '02-00-00-00-10-0F', '02000000100F', '02000000100F', 'NB-15', 'NB5-23-00013', '사무자동화체계', '02000000100F'),
    ROW(1004, 0, '업무망', '192.0.2.19', '02-00-00-00-10-10', '020000001010', '020000001010', 'PR-M400', 'PRM-16-00001', '사무자동화체계', '020000001010'),
    ROW(1006, 0, '업무망', '192.0.2.20', '02-00-00-00-10-11', '020000001011', '020000001011', 'SW-2400', 'S24-19-00001', '부대 네트워크 기반체계', '020000001011'),
    ROW(1008, 0, '업무망', '192.0.2.21', '02-00-00-00-10-12', '020000001012', '020000001012', 'DT-7400', 'DT7-15-00003', '사무자동화체계', '020000001012'),
    ROW(1009, 0, '업무망', '192.0.2.22', '02-00-00-00-10-13', '020000001013', '020000001013', 'NB-15', 'NB5-24-00014', '사무자동화체계', '020000001013'),
    ROW(1012, 0, '업무망', '192.0.2.23', '02-00-00-00-10-14', '020000001014', '020000001014', 'SW-2400', 'S24-23-00002', '부대 네트워크 기반체계', '020000001014'),
    ROW(1017, 0, '업무망', '192.0.2.24', '02-00-00-00-10-15', '020000001015', '020000001015', 'SW-2400', 'S24-18-00003', '부대 네트워크 기반체계', '020000001015'),
    ROW(1020, 0, '업무망', '192.0.2.25', '02-00-00-00-10-16', '020000001016', '020000001016', 'TB-10', 'TB1-15-00001', '전장정보 단말체계', '020000001016'),
    ROW(1022, 0, '업무망', '192.0.2.26', '02-00-00-00-10-17', '020000001017', '020000001017', 'DT-7400', 'DT7-15-00004', '사무자동화체계', '020000001017'),
    ROW(1023, 0, '업무망', '192.0.2.27', '02-00-00-00-10-18', '020000001018', '020000001018', 'DT-7400', 'DT7-21-00005', '사무자동화체계', '020000001018'),
    ROW(1024, 0, '업무망', '192.0.2.28', '02-00-00-00-10-19', '020000001019', '020000001019', 'NB-15', 'NB5-22-00015', '사무자동화체계', '020000001019'),
    ROW(1029, 0, '업무망', '192.0.2.29', '02-00-00-00-10-1A', '02000000101A', '02000000101A', 'DT-7400', 'DT7-21-00006', '사무자동화체계', '02000000101A'),
    ROW(1030, 0, '업무망', '192.0.2.30', '02-00-00-00-10-1B', '02000000101B', '02000000101B', 'DT-7400', 'DT7-16-00007', '사무자동화체계', '02000000101B'),
    ROW(1031, 0, '업무망', '192.0.2.31', '02-00-00-00-10-1C', '02000000101C', '02000000101C', 'NB-15', 'NB5-25-00016', '사무자동화체계', '02000000101C'),
    ROW(1036, 0, '업무망', '192.0.2.32', '02-00-00-00-10-1D', '02000000101D', '02000000101D', 'DT-7400', 'DT7-25-00008', '사무자동화체계', '02000000101D'),
    ROW(1037, 0, '업무망', '192.0.2.33', '02-00-00-00-10-1E', '02000000101E', '02000000101E', 'DT-7400', 'DT7-16-00009', '사무자동화체계', '02000000101E'),
    ROW(1038, 0, '업무망', '192.0.2.34', '02-00-00-00-10-1F', '02000000101F', '02000000101F', 'NB-15', 'NB5-20-00017', '사무자동화체계', '02000000101F'),
    ROW(1039, 0, '업무망', '192.0.2.35', '02-00-00-00-10-20', '020000001020', '020000001020', 'PR-M400', 'PRM-19-00002', '사무자동화체계', '020000001020'),
    ROW(1040, 0, '업무망', '192.0.2.36', '02-00-00-00-10-21', '020000001021', '020000001021', 'SW-2400', 'S24-24-00004', '부대 네트워크 기반체계', '020000001021'),
    ROW(1042, 0, '업무망', '192.0.2.37', '02-00-00-00-10-22', '020000001022', '020000001022', 'TB-10', 'TB1-18-00002', '전장정보 단말체계', '020000001022'),
    ROW(1043, 0, '업무망', '198.51.100.16', '02-00-00-00-10-23', '020000001023', '020000001023', 'NB-15', 'NB5-19-00018', '사무자동화체계', '020000001023'),
    ROW(1044, 0, '업무망', '198.51.100.17', '02-00-00-00-10-24', '020000001024', NULL, 'PR-M400', 'PRM-16-00003', '사무자동화체계', '020000001024'),
    ROW(1050, 0, '업무망', '198.51.100.18', '02-00-00-00-10-25', '020000001025', '020000001025', 'DT-7400', 'DT7-22-00010', '사무자동화체계', '020000001025'),
    ROW(1051, 0, '업무망', '198.51.100.19', '02-00-00-00-10-26', '020000001026', '020000001026', 'DT-7400', 'DT7-20-00011', '사무자동화체계', '020000001026'),
    ROW(1052, 0, '업무망', '198.51.100.20', '02-00-00-00-10-27', '020000001027', '020000001027', 'NB-15', 'NB5-20-00019', '사무자동화체계', '020000001027'),
    ROW(1054, 0, '업무망', '198.51.100.21', '02-00-00-00-10-28', '020000001028', '020000001028', 'SW-2400', 'S24-25-00005', '부대 네트워크 기반체계', '020000001028'),
    ROW(1055, 0, '업무망', '198.51.100.22', '02-00-00-00-10-29', '020000001029', '020000001029', 'TB-10', 'TB1-21-00003', '전장정보 단말체계', '020000001029'),
    ROW(1057, 0, '업무망', '198.51.100.23', '02-00-00-00-10-2A', '02000000102A', '02000000102A', 'DT-7400', 'DT7-22-00012', '사무자동화체계', '02000000102A'),
    ROW(1058, 0, '업무망', '198.51.100.24', '02-00-00-00-10-2B', '02000000102B', '02000000102B', 'NB-15', 'NB5-24-00020', '사무자동화체계', '02000000102B'),
    ROW(1059, 0, '업무망', '198.51.100.25', '02-00-00-00-10-2C', '02000000102C', '02000000102C', 'PR-M400', 'PRM-21-00004', '사무자동화체계', '02000000102C'),
    ROW(1064, 0, '업무망', '198.51.100.26', '02-00-00-00-10-2D', '02000000102D', '02000000102D', 'DT-7400', 'DT7-21-00013', '사무자동화체계', '02000000102D'),
    ROW(1065, 0, '업무망', '198.51.100.27', '02-00-00-00-10-2E', '02000000102E', '02000000102E', 'PR-M400', 'PRM-25-00005', '사무자동화체계', '02000000102E'),
    ROW(1067, 0, '업무망', '198.51.100.28', '02-00-00-00-10-2F', '02000000102F', '02000000102F', 'SW-2400', 'S24-19-00006', '부대 네트워크 기반체계', '02000000102F'),
    ROW(1069, 0, '업무망', '198.51.100.29', '02-00-00-00-10-30', '020000001030', '020000001030', 'TB-10', 'TB1-17-00004', '전장정보 단말체계', '020000001030'),
    ROW(1071, 0, '업무망', '198.51.100.30', '02-00-00-00-10-31', '020000001031', '020000001031', 'NB-15', 'NB5-22-00021', '사무자동화체계', '020000001031'),
    ROW(1072, 0, '업무망', '198.51.100.31', '02-00-00-00-10-32', '020000001032', '020000001032', 'PR-M400', 'PRM-23-00006', '사무자동화체계', '020000001032'),
    ROW(1076, 0, '업무망', '198.51.100.32', '02-00-00-00-10-33', '020000001033', '020000001033', 'TB-10', 'TB1-23-00005', '전장정보 단말체계', '020000001033'),
    ROW(1078, 0, '업무망', '198.51.100.33', '02-00-00-00-10-34', '020000001034', '020000001034', 'DT-7400', 'DT7-15-00014', '사무자동화체계', '020000001034'),
    ROW(1079, 0, '업무망', '198.51.100.34', '02-00-00-00-10-35', '020000001035', '020000001035', 'NB-15', 'NB5-22-00022', '사무자동화체계', '020000001035'),
    ROW(1084, 0, '업무망', '198.51.100.35', '02-00-00-00-10-36', '020000001036', '020000001036', 'TB-10', 'TB1-20-00006', '전장정보 단말체계', '020000001036'),
    ROW(1085, 0, '업무망', '192.0.2.38', '02-00-00-00-10-37', '020000001037', '020000001037', 'MF-6500', 'MF6-22-00001', '사무자동화체계', '020000001037'),
    ROW(1086, 0, '업무망', '192.0.2.39', '02-00-00-00-10-38', '020000001038', '020000001038', 'VC-300', 'VC3-22-00001', '영상회의체계', '020000001038'),
    ROW(1087, 0, '업무망', '192.0.2.40', '02-00-00-00-10-39', '020000001039', '020000001039', 'DT-7400', 'DT7-16-00015', '사무자동화체계', '020000001039'),
    ROW(1088, 0, '업무망', '192.0.2.41', '02-00-00-00-10-3A', '02000000103A', '02000000103A', 'MF-6500', 'MF6-21-00002', '사무자동화체계', '02000000103A'),
    ROW(1089, 0, '업무망', '192.0.2.42', '02-00-00-00-10-3B', '02000000103B', '02000000103B', 'VC-300', 'VC3-15-00002', '영상회의체계', '02000000103B'),
    ROW(1090, 0, '업무망', '192.0.2.43', '02-00-00-00-10-3C', '02000000103C', '02000000103C', 'DT-7400', 'DT7-20-00016', '사무자동화체계', '02000000103C'),
    ROW(1091, 0, '업무망', '192.0.2.44', '02-00-00-00-10-3D', '02000000103D', '02000000103D', 'MF-6500', 'MF6-15-00003', '사무자동화체계', '02000000103D'),
    ROW(1092, 0, '업무망', '192.0.2.45', '02-00-00-00-10-3E', '02000000103E', '02000000103E', 'VC-300', 'VC3-16-00003', '영상회의체계', '02000000103E'),
    ROW(1093, 0, '업무망', '192.0.2.46', '02-00-00-00-10-3F', '02000000103F', '02000000103F', 'DT-7400', 'DT7-18-00017', '사무자동화체계', '02000000103F'),
    ROW(1094, 0, '업무망', '198.51.100.36', '02-00-00-00-10-40', '020000001040', '020000001040', 'MF-6500', 'MF6-18-00004', '사무자동화체계', '020000001040'),
    ROW(1095, 0, '업무망', '198.51.100.37', '02-00-00-00-10-41', '020000001041', '020000001041', 'VC-300', 'VC3-22-00004', '영상회의체계', '020000001041'),
    ROW(1096, 0, '업무망', '198.51.100.38', '02-00-00-00-10-42', '020000001042', NULL, 'DT-7400', 'DT7-23-00018', '사무자동화체계', '020000001042'),
    ROW(1097, 0, '업무망', '198.51.100.39', '02-00-00-00-10-43', '020000001043', '020000001043', 'MF-6500', 'MF6-24-00005', '사무자동화체계', '020000001043'),
    ROW(1098, 0, '업무망', '198.51.100.40', '02-00-00-00-10-44', '020000001044', '020000001044', 'VC-300', 'VC3-19-00005', '영상회의체계', '020000001044'),
    ROW(1099, 0, '업무망', '198.51.100.41', '02-00-00-00-10-45', '020000001045', '020000001045', 'DT-7400', 'DT7-19-00019', '사무자동화체계', '020000001045'),
    ROW(1100, 0, '업무망', '198.51.100.42', '02-00-00-00-10-46', '020000001046', '020000001046', 'MF-6500', 'MF6-17-00006', '사무자동화체계', '020000001046'),
    ROW(1101, 0, '업무망', '198.51.100.43', '02-00-00-00-10-47', '020000001047', '020000001047', 'VC-300', 'VC3-23-00006', '영상회의체계', '020000001047'),
    ROW(1102, 0, '업무망', '198.51.100.44', '02-00-00-00-10-48', '020000001048', '020000001048', 'DT-7400', 'DT7-19-00020', '사무자동화체계', '020000001048'),
    ROW(1103, 0, '업무망', '192.0.2.47', '02-00-00-00-10-49', '020000001049', '020000001049', 'SV-R520', 'SVR-22-00001', '부대 네트워크 기반체계', '020000001049,02000000104A'),
    ROW(1103, 1, '관리용', '192.0.2.48', '02-00-00-00-10-4A', '02000000104A', '02000000104A', 'SV-R520', 'SVR-22-00001', '부대 네트워크 기반체계', '020000001049,02000000104A'),
    ROW(1104, 0, '업무망', '192.0.2.49', '02-00-00-00-10-4B', '02000000104B', '02000000104B', 'SV-R520', 'SVR-21-00002', '부대 네트워크 기반체계', '02000000104B,02000000104C'),
    ROW(1104, 1, '관리용', '192.0.2.50', '02-00-00-00-10-4C', '02000000104C', '02000000104C', 'SV-R520', 'SVR-21-00002', '부대 네트워크 기반체계', '02000000104B,02000000104C'),
    ROW(1105, 0, '업무망', '192.0.2.51', '02-00-00-00-10-4D', '02000000104D', '02000000104D', 'GR-3000', 'GR3-17-00001', '부대 네트워크 기반체계', '02000000104D,02000000104E'),
    ROW(1105, 1, '관리용', '192.0.2.52', '02-00-00-00-10-4E', '02000000104E', '02000000104E', 'GR-3000', 'GR3-17-00001', '부대 네트워크 기반체계', '02000000104D,02000000104E'),
    ROW(1106, 0, '업무망', '192.0.2.53', '02-00-00-00-10-4F', '02000000104F', '02000000104F', 'FW-500', 'FW5-16-00001', '부대 네트워크 기반체계', '02000000104F,020000001050'),
    ROW(1106, 1, '관리용', '192.0.2.54', '02-00-00-00-10-50', '020000001050', '020000001050', 'FW-500', 'FW5-16-00001', '부대 네트워크 기반체계', '02000000104F,020000001050'),
    ROW(1107, 0, '업무망', '192.0.2.55', '02-00-00-00-10-51', '020000001051', '020000001051', 'NAS-8B', 'NS8-23-00001', '부대 네트워크 기반체계', '020000001051'),
    ROW(1108, 0, '업무망', '192.0.2.56', '02-00-00-00-10-52', '020000001052', '020000001052', 'NVR-16', 'NV16-19-00001', '영상감시체계', '020000001052'),
    ROW(1109, 0, '업무망', '192.0.2.57', '02-00-00-00-10-53', '020000001053', '020000001053', 'VC-MCU-20', 'MCU-22-00001', '영상회의체계', '020000001053'),
    ROW(1110, 0, '업무망', '198.51.100.45', '02-00-00-00-10-54', '020000001054', '020000001054', 'SV-R520', 'SVR-16-00003', '부대 네트워크 기반체계', '020000001054,020000001055'),
    ROW(1110, 1, '관리용', '198.51.100.46', '02-00-00-00-10-55', '020000001055', '020000001055', 'SV-R520', 'SVR-16-00003', '부대 네트워크 기반체계', '020000001054,020000001055'),
    ROW(1111, 0, '업무망', '198.51.100.47', '02-00-00-00-10-56', '020000001056', '020000001056', 'SV-R520', 'SVR-15-00004', '부대 네트워크 기반체계', '020000001056,020000001057'),
    ROW(1111, 1, '관리용', '198.51.100.48', '02-00-00-00-10-57', '020000001057', '020000001057', 'SV-R520', 'SVR-15-00004', '부대 네트워크 기반체계', '020000001056,020000001057'),
    ROW(1112, 0, '업무망', '198.51.100.49', '02-00-00-00-10-58', '020000001058', NULL, 'GR-3000', 'GR3-24-00002', '부대 네트워크 기반체계', '020000001058,020000001059'),
    ROW(1112, 1, '관리용', '198.51.100.50', '02-00-00-00-10-59', '020000001059', NULL, 'GR-3000', 'GR3-24-00002', '부대 네트워크 기반체계', '020000001058,020000001059'),
    ROW(1113, 0, '업무망', '198.51.100.51', '02-00-00-00-10-5A', '02000000105A', '02000000105A', 'FW-500', 'FW5-16-00002', '부대 네트워크 기반체계', '02000000105A,02000000105B'),
    ROW(1113, 1, '관리용', '198.51.100.52', '02-00-00-00-10-5B', '02000000105B', '02000000105B', 'FW-500', 'FW5-16-00002', '부대 네트워크 기반체계', '02000000105A,02000000105B'),
    ROW(1114, 0, '업무망', '198.51.100.53', '02-00-00-00-10-5C', '02000000105C', '02000000105C', 'NAS-8B', 'NS8-22-00002', '부대 네트워크 기반체계', '02000000105C'),
    ROW(1115, 0, '업무망', '198.51.100.54', '02-00-00-00-10-5D', '02000000105D', '02000000105D', 'NVR-16', 'NV16-15-00002', '영상감시체계', '02000000105D'),
    ROW(1116, 0, '업무망', '198.51.100.55', '02-00-00-00-10-5E', '02000000105E', '02000000105E', 'VC-MCU-20', 'MCU-20-00002', '영상회의체계', '02000000105E')) v
JOIN equipment e ON e.id = v.column_0 AND e.model_name = v.column_7 AND e.serial_number = v.column_8 AND e.system_name = v.column_9
WHERE NOT EXISTS (SELECT 1 FROM equipment_network n WHERE n.equipment_id = v.column_0)
  AND NOT EXISTS (SELECT 1 FROM equipment_network n WHERE n.equipment_id <> v.column_0 AND FIND_IN_SET(n.active_mac_key, v.column_10));
