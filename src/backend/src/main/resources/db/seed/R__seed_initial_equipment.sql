-- 시연용 장비 데이터 (DEC-080·DEC-144, 명세 밖 시연 도구). scripts/demo-equipment-data.py가 만든 파일이다. 직접 고치지 말고 생성기를 고쳐 다시 돌린다
-- 관리부서 원칙(slices/21 2.2): 쓰려고 받은 장비는 쓰는 부서, 체계 기반 시설은 맡는 반. 놓인 곳은 설치장소
-- 값은 모두 가상(NFR-002): IP 192.0.2.0/24·198.51.100.0/24, MAC 02-00-00-…, 모델명·시리얼넘버·사업명도 가상
-- 서버 기동 때(local·demo 프로필) 초기 데이터 다음에 돈다. 손대지 않은 것만 채우고, 같은 번호·이름·시리얼넘버·MAC이 있으면 그 줄은 건너뛴다

-- 설치장소 (대대 통신실·전산실·회의실·대대장실, 중대 중대본부·통신창고, 반 사무실, 근무지원대대 교육장·보급창고·차량 대기실·복지회관·사이버지식정보방·진료실)

INSERT INTO location (id, department_id, name, active)
SELECT v.column_0, v.column_1, v.column_2, v.column_0 NOT IN (1015) FROM (VALUES
    ROW(1001, 1, '정보통신대대 통신실'),
    ROW(1002, 1, '정보통신대대 전산실'),
    ROW(1003, 1, '정보통신대대 회의실'),
    ROW(1004, 1, '정보통신대대 대대장실'),
    ROW(1005, 11, '근무지원대대 통신실'),
    ROW(1006, 11, '근무지원대대 회의실'),
    ROW(1007, 11, '근무지원대대 대대장실'),
    ROW(1008, 2, '정보통신대대 1중대 중대본부'),
    ROW(1009, 2, '정보통신대대 1중대 통신창고'),
    ROW(1010, 3, '정보통신대대 2중대 중대본부'),
    ROW(1011, 3, '정보통신대대 2중대 통신창고'),
    ROW(1012, 4, '정보통신대대 3중대 중대본부'),
    ROW(1013, 4, '정보통신대대 3중대 통신창고'),
    ROW(1014, 21, '정보통신대대 본부중대 중대본부'),
    ROW(1015, 21, '정보통신대대 본부중대 통신창고'),
    ROW(1016, 12, '근무지원대대 본부중대 중대본부'),
    ROW(1017, 13, '근무지원대대 보급수송중대 중대본부'),
    ROW(1018, 14, '근무지원대대 복지중대 중대본부'),
    ROW(1019, 23, '근무지원대대 의무중대 중대본부'),
    ROW(1020, 12, '근무지원대대 본부중대 교육장'),
    ROW(1021, 13, '근무지원대대 보급수송중대 보급창고'),
    ROW(1022, 13, '근무지원대대 보급수송중대 차량 대기실'),
    ROW(1023, 14, '근무지원대대 복지중대 복지회관'),
    ROW(1024, 14, '근무지원대대 복지중대 사이버지식정보방'),
    ROW(1025, 23, '근무지원대대 의무중대 진료실'),
    ROW(1026, 5, '정보통신대대 1중대 유선반 사무실'),
    ROW(1027, 6, '정보통신대대 1중대 무선반 사무실'),
    ROW(1028, 7, '정보통신대대 2중대 전장망PC반 사무실'),
    ROW(1029, 8, '정보통신대대 2중대 화상회의반 사무실'),
    ROW(1030, 9, '정보통신대대 3중대 네트워크반 사무실'),
    ROW(1031, 10, '정보통신대대 3중대 서버반 사무실'),
    ROW(1032, 22, '정보통신대대 본부중대 정보통신상황실'),
    ROW(1033, 15, '근무지원대대 본부중대 행정반 사무실'),
    ROW(1034, 16, '근무지원대대 본부중대 인사교육반 사무실'),
    ROW(1035, 17, '근무지원대대 보급수송중대 보급반 사무실'),
    ROW(1036, 18, '근무지원대대 보급수송중대 수송반 사무실'),
    ROW(1037, 19, '근무지원대대 복지중대 복지반 사무실'),
    ROW(1038, 20, '근무지원대대 복지중대 체육문화반 사무실'),
    ROW(1039, 24, '근무지원대대 의무중대 의무행정반 사무실'),
    ROW(1040, 25, '근무지원대대 의무중대 진료반 사무실')) v
WHERE NOT EXISTS (SELECT 1 FROM location l WHERE l.id = v.column_0 OR l.active_name = v.column_2);

-- 초기 장비 36대: 손대지 않은 장비만 항목을 채운다(V14 임시 값 '미상'·'MIG-번호'면 처음 모델명·시리얼넘버로). 반출 여부·현재 위치는 그대로

UPDATE equipment e JOIN (
  SELECT column_0 AS id, column_1 AS model, column_2 AS serial, column_3, column_4, column_5, column_6, column_7, column_8, column_9, column_10, column_11, column_12, column_13, column_14, column_15, column_16 FROM (VALUES
    ROW(1, 'RT-100', 'SN-RT-0001', '전술무전체계', '1중대 무선반 휴대용 무전기 1호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 무선반 사무실'), NULL, '메인보드 1.3', '2023-02-21', '펌웨어', '2.4.0', '2023-09-05', 'BATTLEFIELD', '2023-02-21', '2023년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE'),
    ROW(2, 'GEN-5K', 'SN-GN-0001', '전원체계', '1중대 무선반 야전 발전기 1호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 통신창고'), NULL, 'Rev.A', '2023-04-29', NULL, NULL, NULL, NULL, '2023-04-29', '2023년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'UNUSED'),
    ROW(3, 'NB-14', 'SN-NB-0001', '사무자동화체계', '2중대 전장망PC반 업무용 노트북 1호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 2중대 전장망PC반 사무실'), NULL, 'HW 2.1', '2025-06-05', 'Windows 11 Pro', '24H2', '2026-07-18', 'DEFENSE', '2025-06-05', '2025년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE'),
    ROW(4, 'RT-100', 'SN-RT-0002', '전술무전체계', '1중대 무선반 휴대용 무전기 2호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 무선반 사무실'), NULL, 'Rev.C', '2021-07-16', '펌웨어', '2.5.1', '2022-07-28', 'BATTLEFIELD', '2021-07-16', '2021년 전술통신 장비 도입 사업', NULL, 'UNUSED'),
    ROW(5, 'GEN-5K', 'SN-GN-0002', '전원체계', '1중대 무선반 야전 발전기 2호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 통신창고'), '배터리 교체 필요', 'Rev.A', '2015-06-27', NULL, NULL, NULL, NULL, '2015-06-27', '2015년 전원 장비 교체 사업', NULL, 'IN_USE'),
    ROW(6, 'NB-14', 'SN-NB-0002', '사무자동화체계', '2중대 전장망PC반 업무용 노트북 2호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 2중대 전장망PC반 사무실'), NULL, 'Rev.A', '2016-10-26', 'Windows 11 Pro', '24H2', '2017-06-28', 'DEFENSE', '2016-10-26', '2016년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE'),
    ROW(7, 'RT-100', 'SN-RT-0003', '전술무전체계', '1중대 무선반 휴대용 무전기 3호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 무선반 사무실'), '키보드·마우스 교체(2025)', 'Rev.A', '2015-08-19', '펌웨어', '2.5.1', '2016-03-05', 'BATTLEFIELD', '2015-08-19', '2015년 전술통신 장비 도입 사업', NULL, 'IN_USE'),
    ROW(8, 'GEN-5K', 'SN-GN-0003', '전원체계', '1중대 무선반 야전 발전기 3호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 통신창고'), NULL, 'Rev.A', '2021-04-15', NULL, NULL, NULL, NULL, '2021-04-15', '2021년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'IN_USE'),
    ROW(9, 'NB-14', 'SN-NB-0003', '사무자동화체계', '2중대 전장망PC반 업무용 노트북 3호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 2중대 전장망PC반 사무실'), NULL, '메인보드 1.3', '2018-12-16', 'Windows 11 Pro', '23H2', '2018-12-29', 'DEFENSE', '2018-12-16', '2018년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE'),
    ROW(10, 'RT-100', 'SN-RT-0004', '전술무전체계', '1중대 무선반 휴대용 무전기 4호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 무선반 사무실'), NULL, 'Rev.B', '2024-10-15', '펌웨어', '2.4.0', '2024-12-10', 'BATTLEFIELD', '2024-10-15', '2024년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE'),
    ROW(11, 'GEN-5K', 'SN-GN-0004', '전원체계', '1중대 무선반 야전 발전기 4호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 통신창고'), NULL, 'HW 1.0', '2021-12-12', NULL, NULL, NULL, NULL, '2021-12-12', '2021년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'IN_USE'),
    ROW(12, 'NB-14', 'SN-NB-0004', '사무자동화체계', '본부중대 정보통신상황실 업무용 노트북 4호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 본부중대 정보통신상황실'), NULL, '메인보드 1.3', '2018-05-09', 'Windows 11 Pro', '23H2', '2018-07-03', 'DEFENSE', '2018-05-09', '2018년 사무자동화 장비 보강 사업', NULL, 'IN_USE'),
    ROW(13, 'RT-100', 'SN-RT-0005', '전술무전체계', '본부중대 정보통신상황실 휴대용 무전기 5호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 본부중대 정보통신상황실'), NULL, 'HW 2.1', '2025-11-28', '펌웨어', '2.5.1', '2026-09-12', 'BATTLEFIELD', '2025-11-28', '2025년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE'),
    ROW(14, 'GEN-5K', 'SN-GN-0005', '전원체계', '1중대 무선반 야전 발전기 5호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 통신창고'), NULL, 'Rev.B', '2024-06-09', NULL, NULL, NULL, NULL, '2024-06-09', '2024년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'UNUSED'),
    ROW(15, 'NB-14', 'SN-NB-0005', '사무자동화체계', '1중대 유선반 업무용 노트북 5호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 유선반 사무실'), NULL, '메인보드 1.3', '2022-06-21', 'Windows 11 Pro', '23H2', '2022-12-19', 'DEFENSE', '2022-06-21', '2022년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE'),
    ROW(16, 'RT-100', 'SN-RT-0006', '전술무전체계', '본부중대 정보통신상황실 휴대용 무전기 6호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 본부중대 정보통신상황실'), NULL, 'Rev.C', '2019-04-11', '펌웨어', '2.5.1', '2020-05-07', 'BATTLEFIELD', '2019-04-11', '2019년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'UNUSED'),
    ROW(17, 'GEN-5K', 'SN-GN-0006', '전원체계', '1중대 무선반 야전 발전기 6호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 통신창고'), NULL, 'HW 2.1', '2024-03-10', NULL, NULL, NULL, NULL, '2024-03-10', '2024년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'UNUSED'),
    ROW(18, 'NB-14', 'SN-NB-0006', '사무자동화체계', '2중대 화상회의반 업무용 노트북 6호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 2중대 화상회의반 사무실'), NULL, 'Rev.B', '2017-04-02', 'Windows 11 Pro', '23H2', '2019-09-09', 'DEFENSE', '2017-04-02', '2017년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE'),
    ROW(19, 'RT-100', 'SN-RT-0007', '전술무전체계', '보급수송중대 보급반 휴대용 무전기 7호', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 보급반 사무실'), NULL, 'HW 2.1', '2022-10-23', '펌웨어', '2.5.1', '2023-01-25', 'BATTLEFIELD', '2022-10-23', '2022년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'UNUSED'),
    ROW(20, 'GEN-5K', 'SN-GN-0007', '전원체계', '보급수송중대 보급반 야전 발전기 7호', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 보급창고'), NULL, 'Rev.C', '2025-06-17', NULL, NULL, NULL, NULL, '2025-06-17', '2025년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'IN_USE'),
    ROW(21, 'NB-14', 'SN-NB-0007', '사무자동화체계', '3중대 네트워크반 업무용 노트북 7호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 3중대 네트워크반 사무실'), '키보드·마우스 교체(2025)', '메인보드 1.3', '2016-02-13', 'Windows 11 Pro', '24H2', '2016-05-25', 'DEFENSE', '2016-02-13', '2016년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE'),
    ROW(22, 'RT-100', 'SN-RT-0008', '전술무전체계', '보급수송중대 보급반 휴대용 무전기 8호', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 보급반 사무실'), NULL, 'HW 2.1', '2022-07-16', '펌웨어', '2.5.1', '2024-11-03', 'BATTLEFIELD', '2022-07-16', '2022년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE'),
    ROW(23, 'GEN-5K', 'SN-GN-0008', '전원체계', '보급수송중대 보급반 야전 발전기 8호', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 보급창고'), NULL, 'Rev.C', '2023-07-30', NULL, NULL, NULL, NULL, '2023-07-30', '2023년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'IN_USE'),
    ROW(24, 'NB-14', 'SN-NB-0008', '사무자동화체계', '3중대 서버반 업무용 노트북 8호', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 3중대 서버반 사무실'), '정기 점검 대상', 'HW 1.0', '2018-08-30', 'Windows 11 Pro', '24H2', '2019-12-16', 'DEFENSE', '2018-08-30', '2018년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE'),
    ROW(25, 'RT-100', 'SN-RT-0009', '전술무전체계', '보급수송중대 수송반 휴대용 무전기 9호', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 수송반 사무실'), '예비품으로 보관', '메인보드 1.3', '2018-03-22', '펌웨어', '2.5.1', '2018-11-24', 'BATTLEFIELD', '2018-03-22', '2018년 전술통신 장비 도입 사업', NULL, 'IN_USE'),
    ROW(26, 'GEN-5K', 'SN-GN-0009', '전원체계', '보급수송중대 보급반 야전 발전기 9호', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 보급창고'), NULL, 'Rev.A', '2022-03-14', NULL, NULL, NULL, NULL, '2022-03-14', '2022년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'IN_USE'),
    ROW(27, 'NB-14', 'SN-NB-0009', '사무자동화체계', '본부중대 인사교육반 업무용 노트북 9호', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 본부중대 인사교육반 사무실'), NULL, 'Rev.C', '2024-09-12', 'Windows 11 Pro', '23H2', '2026-09-30', 'DEFENSE', '2024-09-12', '2024년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE'),
    ROW(28, 'RT-100', 'SN-RT-0010', '전술무전체계', '보급수송중대 수송반 휴대용 무전기 10호', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 수송반 사무실'), NULL, 'Rev.B', '2017-07-26', '펌웨어', '2.4.0', '2019-04-11', 'BATTLEFIELD', '2017-07-26', '2017년 전술통신 장비 도입 사업', NULL, 'IN_USE'),
    ROW(29, 'GEN-5K', 'SN-GN-0010', '전원체계', '보급수송중대 보급반 야전 발전기 10호', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 보급창고'), NULL, 'Rev.A', '2021-05-31', NULL, NULL, NULL, NULL, '2021-05-31', '2021년 전원 장비 교체 사업', NULL, 'IN_USE'),
    ROW(30, 'NB-14', 'SN-NB-0010', '사무자동화체계', '의무중대 진료반 업무용 노트북 10호', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 의무중대 진료반 사무실'), NULL, 'HW 2.1', '2018-02-23', 'Windows 11 Pro', '23H2', '2019-12-11', 'DEFENSE', '2018-02-23', '2018년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE'),
    ROW(31, 'RT-100', 'SN-RT-0011', '전술무전체계', '본부중대 행정반 휴대용 무전기 11호', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 본부중대 행정반 사무실'), NULL, '메인보드 1.3', '2016-01-01', '펌웨어', '2.5.1', '2016-08-04', 'BATTLEFIELD', '2016-01-01', '2016년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE'),
    ROW(32, 'GEN-5K', 'SN-GN-0011', '전원체계', '보급수송중대 보급반 야전 발전기 11호', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 보급창고'), NULL, 'HW 2.1', '2022-02-10', NULL, NULL, NULL, NULL, '2022-02-10', '2022년 전원 장비 교체 사업', NULL, 'IN_USE'),
    ROW(33, 'NB-14', 'SN-NB-0011', '사무자동화체계', '복지중대 복지반 업무용 노트북 11호', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 복지반 사무실'), NULL, '메인보드 1.3', '2018-06-21', 'Windows 11 Pro', '24H2', '2018-09-25', 'DEFENSE', '2018-06-21', '2018년 사무자동화 장비 보강 사업', NULL, 'IN_USE'),
    ROW(34, 'RT-100', 'SN-RT-0012', '전술무전체계', '의무중대 의무행정반 휴대용 무전기 12호', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 의무중대 의무행정반 사무실'), NULL, '메인보드 1.3', '2018-07-20', '펌웨어', '2.4.0', '2018-11-30', 'BATTLEFIELD', '2018-07-20', '2018년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE'),
    ROW(35, 'GEN-5K', 'SN-GN-0012', '전원체계', '보급수송중대 보급반 야전 발전기 12호', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 보급창고'), NULL, 'Rev.A', '2024-10-06', NULL, NULL, NULL, NULL, '2024-10-06', '2024년 전원 장비 교체 사업', '2026년 전원장비 점검 지원', 'IN_USE'),
    ROW(36, 'NB-14', 'SN-NB-0012', '사무자동화체계', '보급수송중대 수송반 업무용 노트북 12호', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 수송반 사무실'), NULL, 'Rev.B', '2025-08-10', 'Windows 11 Pro', '23H2', '2026-09-30', 'DEFENSE', '2025-08-10', '2025년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE')) x
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
    e.edited_at = TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
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
SELECT 1001 AS id, '대형 화면 1호' AS name, 22 AS department_id, 'DS-75' AS model_name, 'DS7-22-00001' AS serial_number, '영상표출체계' AS system_name, '본부중대 정보통신상황실 대형 화면 1' AS common_name, (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 본부중대 정보통신상황실') AS storage_location_id, '화면 출력 안 됨' AS memo, 'Rev.C' AS hw_version, '2022-12-10' AS hw_installed_on, '펌웨어' AS os_type, '2.2.0' AS os_version, '2025-01-11' AS os_installed_on, 'DEFENSE' AS network_kind, '2022-12-10' AS introduced_on, '2022년 영상표출 장비 도입 사업' AS intro_project, '2026년 영상회의 체계 유지보수' AS maintenance_project, 'BROKEN' AS status, 'NONE' AS carry_out, NULL AS current_department_id, NULL AS current_place, NULL AS carry_out_reason, NULL AS carry_out_by_account_id, NULL AS carry_out_at, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY AS edited_at
UNION ALL SELECT 1002, '행정용 PC 1호', 22, 'DT-7400', 'DT7-23-00001', '사무자동화체계', '본부중대 정보통신상황실 행정용 PC 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 본부중대 정보통신상황실'), NULL, 'Rev.B', '2023-12-25', 'Windows 11 Pro', '24H2', '2024-10-02', 'DEFENSE', '2023-12-25', '2023년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1003, '행정용 PC 2호', 22, 'DT-7400', 'DT7-16-00002', '사무자동화체계', '본부중대 정보통신상황실 행정용 PC 2', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 본부중대 정보통신상황실'), NULL, '메인보드 1.3', '2016-02-10', 'Windows 11 Pro', '24H2', '2018-04-02', 'DEFENSE', '2016-02-10', '2016년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1004, '전장망 단말 1호', 22, 'BT-12', 'BT1-18-00001', '전장정보 단말체계', '본부중대 정보통신상황실 전장망 단말 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 본부중대 정보통신상황실'), NULL, 'Rev.C', '2018-05-28', '단말 OS', '5.3', '2019-10-28', 'BATTLEFIELD', '2018-05-28', '2018년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1005, '일반전화기 1호', 22, 'TP-200', 'TP2-18-00001', '군 전화체계', '본부중대 정보통신상황실 일반전화기 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 본부중대 정보통신상황실'), '외부 정비 입고, 부품 수급 대기', 'HW 2.1', '2018-07-25', NULL, NULL, NULL, NULL, '2018-07-25', '2018년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'MAINTENANCE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1006, '일반전화기 2호', 22, 'TP-200', 'TP2-21-00002', '군 전화체계', '본부중대 정보통신상황실 일반전화기 2', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 본부중대 정보통신상황실'), NULL, 'HW 2.1', '2021-02-19', NULL, NULL, NULL, NULL, '2021-02-19', '2021년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1007, '구내 교환기 1호', 5, 'PBX-600', 'PBX-24-00001', '군 전화체계', '1중대 유선반 구내 교환기 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 통신실'), NULL, 'Rev.B', '2024-05-01', '펌웨어', '6.2.1', '2026-07-31', 'DEFENSE', '2024-05-01', '2024년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1008, '구내 교환기 2호', 5, 'PBX-600', 'PBX-19-00002', '군 전화체계', '1중대 유선반 구내 교환기 2', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 통신실'), NULL, 'HW 2.1', '2019-12-26', '펌웨어', '6.2.1', '2021-01-10', 'DEFENSE', '2019-12-26', '2019년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1009, '일반전화기 3호', 5, 'TP-200', 'TP2-20-00003', '군 전화체계', '1중대 유선반 일반전화기 3', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 유선반 사무실'), '정기 정비 입고', 'Rev.B', '2020-04-11', NULL, NULL, NULL, NULL, '2020-04-11', '2020년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'MAINTENANCE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1010, '일반전화기 4호', 5, 'TP-200', 'TP2-24-00004', '군 전화체계', '1중대 유선반 일반전화기 4', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 유선반 사무실'), NULL, 'Rev.B', '2024-05-26', NULL, NULL, NULL, NULL, '2024-05-26', '2024년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1011, '일반전화기 5호', 5, 'TP-200', 'TP2-19-00005', '군 전화체계', '1중대 유선반 일반전화기 5', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 유선반 사무실'), NULL, 'HW 2.1', '2019-01-20', NULL, NULL, NULL, NULL, '2019-01-20', '2019년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1012, '케이블 시험기 1호', 5, 'CT-90', 'CT9-18-00001', '측정장비', '1중대 유선반 케이블 시험기 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 통신창고'), '화면 출력 안 됨', 'Rev.C', '2018-11-26', NULL, NULL, NULL, NULL, '2018-11-26', '2018년 정비 장비 보강 사업', NULL, 'BROKEN', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1013, '행정용 PC 3호', 5, 'DT-7400', 'DT7-23-00003', '사무자동화체계', '1중대 유선반 행정용 PC 3', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 유선반 사무실'), '불용 심의 끝, 불용 처리(내용연수 경과)', 'Rev.C', '2023-09-30', 'Windows 11 Pro', '23H2', '2024-12-13', 'DEFENSE', '2023-09-30', '2023년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'DISPOSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1014, '기지국 무전기 1호', 6, 'BR-500', 'BR5-24-00001', '전술무전체계', '1중대 무선반 기지국 무전기 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 통신실'), NULL, 'HW 1.0', '2024-11-02', '펌웨어', '3.1.0', '2025-08-28', 'BATTLEFIELD', '2024-11-02', '2024년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1015, '중계기 1호', 6, 'RP-30', 'RP3-18-00001', '전술무전체계', '1중대 무선반 중계기 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 통신실'), NULL, 'HW 1.0', '2018-07-22', '펌웨어', '1.4.2', '2018-07-22', 'BATTLEFIELD', '2018-07-22', '2018년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1016, '차량용 무전기 1호', 6, 'VR-50', 'V5-19-00001', '전술무전체계', '1중대 무선반 차량용 무전기 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 통신창고'), NULL, 'HW 2.1', '2019-02-21', '펌웨어', '1.9.3', '2020-12-28', 'BATTLEFIELD', '2019-02-21', '2019년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1017, '차량용 무전기 2호', 6, 'VR-50', 'V5-21-00002', '전술무전체계', '1중대 무선반 차량용 무전기 2', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 통신창고'), '화면 출력 안 됨', 'HW 2.1', '2021-08-01', '펌웨어', '1.9.3', '2022-03-26', 'BATTLEFIELD', '2021-08-01', '2021년 전술통신 장비 도입 사업', NULL, 'BROKEN', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1018, '통화 녹음기 1호', 6, 'CR-16', 'CR1-23-00001', '녹음체계', '1중대 무선반 통화 녹음기 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 통신실'), '정기 정비 입고', 'Rev.C', '2023-09-28', '펌웨어', '2.0.6', '2024-03-26', 'DEFENSE', '2023-09-28', '2023년 녹음 장비 교체 사업', '2026년 정보통신장비 통합 유지보수', 'MAINTENANCE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1019, '무전 녹음기 1호', 6, 'RR-8', 'RR8-16-00001', '녹음체계', '1중대 무선반 무전 녹음기 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 본부중대 정보통신상황실'), NULL, 'Rev.C', '2016-12-02', '펌웨어', '1.2.0', '2017-09-24', 'BATTLEFIELD', '2016-12-02', '2016년 녹음 장비 교체 사업', '2026년 전술통신 장비 정비 지원', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1020, '구내방송 앰프 1호', 6, 'PA-240', 'PA2-20-00001', '구내방송체계', '1중대 무선반 구내방송 앰프 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 통신실'), NULL, 'Rev.A', '2020-02-17', NULL, NULL, NULL, NULL, '2020-02-17', '2020년 구내방송 장비 교체 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1021, '행정용 PC 4호', 6, 'DT-7400', 'DT7-16-00004', '사무자동화체계', '1중대 무선반 행정용 PC 4', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 무선반 사무실'), NULL, 'HW 1.0', '2016-02-29', 'Windows 11 Pro', '23H2', '2017-01-09', 'DEFENSE', '2016-02-29', '2016년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1022, '전장망 단말 2호', 7, 'BT-12', 'BT1-17-00002', '전장정보 단말체계', '2중대 전장망PC반 전장망 단말 2', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 2중대 전장망PC반 사무실'), NULL, 'HW 1.0', '2017-05-29', '단말 OS', '5.4', '2017-06-01', 'BATTLEFIELD', '2017-05-29', '2017년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1023, '전장망 단말 3호', 7, 'BT-12', 'BT1-15-00003', '전장정보 단말체계', '2중대 전장망PC반 전장망 단말 3', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 2중대 전장망PC반 사무실'), NULL, '메인보드 1.3', '2015-12-17', '단말 OS', '5.4', '2017-03-19', 'BATTLEFIELD', '2015-12-17', '2015년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1024, '행정용 PC 5호', 7, 'DT-7400', 'DT7-23-00005', '사무자동화체계', '2중대 전장망PC반 행정용 PC 5', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 2중대 전장망PC반 사무실'), '송신 불량', 'Rev.B', '2023-12-13', 'Windows 11 Pro', '24H2', '2024-03-24', 'DEFENSE', '2023-12-13', '2023년 사무자동화 장비 보강 사업', NULL, 'BROKEN', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1025, '행정용 PC 6호', 7, 'DT-7400', 'DT7-25-00006', '사무자동화체계', '2중대 전장망PC반 행정용 PC 6', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 2중대 전장망PC반 사무실'), NULL, '메인보드 1.3', '2025-06-09', 'Windows 11 Pro', '24H2', '2025-09-02', 'DEFENSE', '2025-06-09', '2025년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1026, '모니터 1호', 7, 'MN-27', 'MN2-15-00001', '사무자동화체계', '2중대 전장망PC반 모니터 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 2중대 전장망PC반 사무실'), NULL, 'Rev.B', '2015-05-13', NULL, NULL, NULL, NULL, '2015-05-13', '2015년 사무자동화 장비 보강 사업', NULL, 'UNUSED', 'OUT', 22, NULL, '교육 지원 대여', (SELECT MIN(a.id) FROM account a JOIN personnel p ON p.id = a.personnel_id WHERE p.department_id = 7 AND a.active), TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 20 DAY, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 20 DAY
UNION ALL SELECT 1027, '모니터 2호', 7, 'MN-27', 'MN2-20-00002', '사무자동화체계', '2중대 전장망PC반 모니터 2', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 2중대 전장망PC반 사무실'), NULL, 'Rev.A', '2020-10-05', NULL, NULL, NULL, NULL, '2020-10-05', '2020년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1028, '업무용 노트북 13호', 7, 'NB-15', 'NB5-24-00013', '사무자동화체계', '2중대 전장망PC반 업무용 노트북 13', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 2중대 전장망PC반 사무실'), '내용연수 경과, 불용 심의 대기', 'Rev.B', '2024-04-20', 'Windows 11 Pro', '24H2', '2025-10-01', 'DEFENSE', '2024-04-20', '2024년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'DISPOSAL_PENDING', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1029, '레이저 프린터 1호', 7, 'PR-M400', 'PRM-15-00001', '사무자동화체계', '2중대 전장망PC반 레이저 프린터 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 2중대 전장망PC반 사무실'), '화면 출력 안 됨', 'Rev.C', '2015-02-24', '펌웨어', '3.2.1', '2017-06-29', 'DEFENSE', '2015-02-24', '2015년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'BROKEN', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1030, '영상회의 서버 1호', 8, 'VC-MCU-20', 'MCU-18-00001', '영상회의체계', '2중대 화상회의반 영상회의 서버 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 전산실'), NULL, '메인보드 1.3', '2018-11-17', 'Linux', '5.15', '2021-04-18', 'DEFENSE', '2018-11-17', '2018년 영상회의 체계 구축 사업', '2026년 영상회의 체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1031, '영상회의 단말 1호', 8, 'VC-300', 'VC3-22-00001', '영상회의체계', '2중대 화상회의반 영상회의 단말 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 회의실'), '수리 비용 과다, 불용 심의 대기', '메인보드 1.3', '2022-03-07', '펌웨어', '8.1.0', '2023-10-11', 'DEFENSE', '2022-03-07', '2022년 영상회의 체계 구축 사업', '2026년 영상회의 체계 유지보수', 'DISPOSAL_PENDING', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1032, '영상회의 단말 2호', 8, 'VC-300', 'VC3-17-00002', '영상회의체계', '2중대 화상회의반 영상회의 단말 2', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 회의실'), '정기 점검 대상', 'HW 1.0', '2017-07-30', '펌웨어', '8.1.0', '2018-02-24', 'DEFENSE', '2017-07-30', '2017년 영상회의 체계 구축 사업', '2026년 영상회의 체계 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1033, '영상회의 단말 3호', 8, 'VC-300', 'VC3-19-00003', '영상회의체계', '2중대 화상회의반 영상회의 단말 3', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 본부중대 교육장'), NULL, 'HW 2.1', '2019-09-06', '펌웨어', '8.1.0', '2021-09-08', 'DEFENSE', '2019-09-06', '2019년 영상회의 체계 구축 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1034, '회의실 카메라 1호', 8, 'CM-4K', 'CM4-18-00001', '영상회의체계', '2중대 화상회의반 회의실 카메라 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 회의실'), '토너 교체 주기 확인', 'HW 1.0', '2018-09-08', NULL, NULL, NULL, NULL, '2018-09-08', '2018년 영상회의 체계 구축 사업', '2026년 영상회의 체계 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1035, '회의실 마이크 1호', 8, 'MC-8', 'MC8-24-00001', '영상회의체계', '2중대 화상회의반 회의실 마이크 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 회의실'), NULL, 'Rev.C', '2024-04-06', NULL, NULL, NULL, NULL, '2024-04-06', '2024년 영상회의 체계 구축 사업', '2026년 영상회의 체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1036, '회의실 스피커 1호', 8, 'SP-2', 'SP2-22-00001', '영상회의체계', '2중대 화상회의반 회의실 스피커 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 회의실'), NULL, 'HW 1.0', '2022-11-19', NULL, NULL, NULL, NULL, '2022-11-19', '2022년 영상회의 체계 구축 사업', '2026년 영상회의 체계 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1037, '회의실 카메라 2호', 8, 'CM-4K', 'CM4-21-00002', '영상회의체계', '2중대 화상회의반 회의실 카메라 2', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 회의실'), NULL, 'Rev.B', '2021-08-21', NULL, NULL, NULL, NULL, '2021-08-21', '2021년 영상회의 체계 구축 사업', '2026년 영상회의 체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1038, '회의실 마이크 2호', 8, 'MC-8', 'MC8-15-00002', '영상회의체계', '2중대 화상회의반 회의실 마이크 2', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 회의실'), NULL, 'Rev.A', '2015-10-15', NULL, NULL, NULL, NULL, '2015-10-15', '2015년 영상회의 체계 구축 사업', '2026년 영상회의 체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1039, '회의실 스피커 2호', 8, 'SP-2', 'SP2-16-00002', '영상회의체계', '2중대 화상회의반 회의실 스피커 2', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 회의실'), NULL, 'Rev.B', '2016-06-25', NULL, NULL, NULL, NULL, '2016-06-25', '2016년 영상회의 체계 구축 사업', '2026년 영상회의 체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1040, '대형 화면 2호', 8, 'DS-75', 'DS7-16-00002', '영상표출체계', '2중대 화상회의반 대형 화면 2', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 회의실'), NULL, 'HW 1.0', '2016-04-19', '펌웨어', '2.2.0', '2017-08-31', 'DEFENSE', '2016-04-19', '2016년 영상표출 장비 도입 사업', '2026년 영상회의 체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1041, '프로젝터 1호', 8, 'PJ-50', 'PJ5-17-00001', '영상표출체계', '2중대 화상회의반 프로젝터 1', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 본부중대 교육장'), NULL, 'Rev.A', '2017-03-04', NULL, NULL, NULL, NULL, '2017-03-04', '2017년 영상표출 장비 도입 사업', '2026년 영상회의 체계 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1042, '프로젝터 2호', 8, 'PJ-50', 'PJ5-18-00002', '영상표출체계', '2중대 화상회의반 프로젝터 2', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 회의실'), NULL, 'Rev.A', '2018-02-15', NULL, NULL, NULL, NULL, '2018-02-15', '2018년 영상표출 장비 도입 사업', '2026년 영상회의 체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1043, '라우터 1호', 9, 'GR-3000', 'GR3-16-00001', '부대 네트워크 기반체계', '3중대 네트워크반 라우터 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 통신실'), '내용연수 경과, 불용 심의 대기', 'HW 1.0', '2016-10-27', 'NOS', '15.2.7', '2019-04-05', 'DEFENSE', '2016-10-27', '2016년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'DISPOSAL_PENDING', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1044, '라우터 2호', 9, 'GR-3000', 'GR3-24-00002', '부대 네트워크 기반체계', '3중대 네트워크반 라우터 2', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 통신실'), NULL, 'Rev.B', '2024-01-15', 'NOS', '15.2.7', '2024-12-18', 'DEFENSE', '2024-01-15', '2024년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1045, '방화벽 1호', 9, 'FW-500', 'FW5-20-00001', '부대 네트워크 기반체계', '3중대 네트워크반 방화벽 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 통신실'), '정기 정비 입고', 'Rev.C', '2020-03-23', '보안 OS', '7.0.15', '2021-10-25', 'DEFENSE', '2020-03-23', '2020년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'MAINTENANCE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1046, '방화벽 2호', 9, 'FW-500', 'FW5-19-00002', '부대 네트워크 기반체계', '3중대 네트워크반 방화벽 2', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 통신실'), '제조사 점검 입고(반출 중)', 'Rev.C', '2019-08-15', '보안 OS', '7.0.15', '2021-06-09', 'DEFENSE', '2019-08-15', '2019년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'MAINTENANCE', 'OUT', NULL, '제조사 서비스센터(가상)', '제조사 점검 입고', (SELECT MIN(a.id) FROM account a JOIN personnel p ON p.id = a.personnel_id WHERE p.department_id = 9 AND a.active), TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 6 DAY, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 6 DAY
UNION ALL SELECT 1047, '백본 스위치 1호', 9, 'CS-9600', 'CS9-15-00001', '부대 네트워크 기반체계', '3중대 네트워크반 백본 스위치 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 통신실'), NULL, 'HW 2.1', '2015-03-27', 'NOS', '17.3.4', '2017-09-01', 'DEFENSE', '2015-03-27', '2015년 부대 네트워크 고도화 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1048, '백본 스위치 2호', 9, 'CS-9600', 'CS9-18-00002', '부대 네트워크 기반체계', '3중대 네트워크반 백본 스위치 2', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 통신실'), '전원 불량, 수리 의뢰 예정', 'HW 1.0', '2018-05-14', 'NOS', '17.3.4', '2020-01-17', 'DEFENSE', '2018-05-14', '2018년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'BROKEN', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1049, '소형 스위치 1호', 9, 'SW-2400', 'S24-20-00001', '부대 네트워크 기반체계', '3중대 네트워크반 소형 스위치 1', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 통신실'), NULL, 'Rev.C', '2020-03-12', 'NOS', '4.2.0', '2020-11-04', 'DEFENSE', '2020-03-12', '2020년 부대 네트워크 고도화 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1050, '소형 스위치 2호', 9, 'SW-2400', 'S24-18-00002', '부대 네트워크 기반체계', '3중대 네트워크반 소형 스위치 2', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 중대본부'), NULL, 'Rev.C', '2018-06-14', 'NOS', '4.2.0', '2019-07-06', 'DEFENSE', '2018-06-14', '2018년 부대 네트워크 고도화 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1051, '소형 스위치 3호', 9, 'SW-2400', 'S24-18-00003', '부대 네트워크 기반체계', '3중대 네트워크반 소형 스위치 3', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 2중대 중대본부'), '정기 정비 입고', 'HW 1.0', '2018-11-01', 'NOS', '4.1.2', '2020-04-11', 'DEFENSE', '2018-11-01', '2018년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'MAINTENANCE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1052, '소형 스위치 4호', 9, 'SW-2400', 'S24-21-00004', '부대 네트워크 기반체계', '3중대 네트워크반 소형 스위치 4', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 3중대 중대본부'), '키보드·마우스 교체(2025)', 'Rev.A', '2021-04-19', 'NOS', '4.2.0', '2022-06-24', 'DEFENSE', '2021-04-19', '2021년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1053, '소형 스위치 5호', 9, 'SW-2400', 'S24-23-00005', '부대 네트워크 기반체계', '3중대 네트워크반 소형 스위치 5', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 본부중대 중대본부'), '외부 정비 입고, 부품 수급 대기(반출 중)', 'Rev.C', '2023-12-06', 'NOS', '4.1.2', '2024-04-23', 'DEFENSE', '2023-12-06', '2023년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'MAINTENANCE', 'OUT', NULL, '외부 정비업체(가상)', '외부 수리 업체 입고', (SELECT MIN(a.id) FROM account a JOIN personnel p ON p.id = a.personnel_id WHERE p.department_id = 9 AND a.active), TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 20 DAY, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 20 DAY
UNION ALL SELECT 1054, '소형 스위치 6호', 9, 'SW-2400', 'S24-21-00006', '부대 네트워크 기반체계', '3중대 네트워크반 소형 스위치 6', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 본부중대 중대본부'), NULL, 'HW 2.1', '2021-12-03', 'NOS', '4.2.0', '2023-04-10', 'DEFENSE', '2021-12-03', '2021년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1055, '소형 스위치 7호', 9, 'SW-2400', 'S24-15-00007', '부대 네트워크 기반체계', '3중대 네트워크반 소형 스위치 7', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 중대본부'), NULL, 'Rev.B', '2015-06-06', 'NOS', '4.1.2', '2016-12-01', 'DEFENSE', '2015-06-06', '2015년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1056, '소형 스위치 8호', 9, 'SW-2400', 'S24-24-00008', '부대 네트워크 기반체계', '3중대 네트워크반 소형 스위치 8', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 중대본부'), NULL, 'HW 1.0', '2024-08-10', 'NOS', '4.1.2', '2025-11-15', 'DEFENSE', '2024-08-10', '2024년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1057, '소형 스위치 9호', 9, 'SW-2400', 'S24-17-00009', '부대 네트워크 기반체계', '3중대 네트워크반 소형 스위치 9', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 의무중대 중대본부'), NULL, 'Rev.C', '2017-10-06', 'NOS', '4.2.0', '2019-06-17', 'DEFENSE', '2017-10-06', '2017년 부대 네트워크 고도화 사업', NULL, 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1058, '소형 스위치 10호', 9, 'SW-2400', 'S24-17-00010', '부대 네트워크 기반체계', '3중대 네트워크반 소형 스위치 10', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 사이버지식정보방'), NULL, 'HW 2.1', '2017-10-09', 'NOS', '4.2.0', '2017-11-01', 'DEFENSE', '2017-10-09', '2017년 부대 네트워크 고도화 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1059, '행정용 PC 7호', 9, 'DT-7400', 'DT7-24-00007', '사무자동화체계', '3중대 네트워크반 행정용 PC 7', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 3중대 네트워크반 사무실'), NULL, 'HW 2.1', '2024-05-18', 'Windows 11 Pro', '24H2', '2026-05-26', 'DEFENSE', '2024-05-18', '2024년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1060, '업무 서버 1호', 10, 'SV-R520', 'SVR-25-00001', '부대 네트워크 기반체계', '3중대 서버반 업무 서버 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 전산실'), NULL, 'Rev.A', '2025-07-09', 'Rocky Linux', '9.4', '2025-08-28', 'DEFENSE', '2025-07-09', '2025년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1061, '업무 서버 2호', 10, 'SV-R520', 'SVR-25-00002', '부대 네트워크 기반체계', '3중대 서버반 업무 서버 2', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 전산실'), '외부 정비 입고, 부품 수급 대기', 'Rev.A', '2025-08-21', 'Rocky Linux', '9.4', '2026-09-30', 'DEFENSE', '2025-08-21', '2025년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'MAINTENANCE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1062, '인트라넷 서버 1호', 10, 'SV-R540', 'SVI-15-00001', '부대 네트워크 기반체계', '3중대 서버반 인트라넷 서버 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 전산실'), '키보드·마우스 교체(2025)', 'Rev.C', '2015-05-29', 'Rocky Linux', '9.4', '2015-11-13', 'DEFENSE', '2015-05-29', '2015년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1063, '저장장치(NAS) 1호', 10, 'NAS-8B', 'NS8-22-00001', '부대 네트워크 기반체계', '3중대 서버반 저장장치(NAS) 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 전산실'), NULL, 'Rev.C', '2022-02-06', 'NAS OS', '7.2.2', '2024-03-25', 'DEFENSE', '2022-02-06', '2022년 부대 네트워크 고도화 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1064, '저장장치(NAS) 2호', 10, 'NAS-8B', 'NS8-23-00002', '부대 네트워크 기반체계', '3중대 서버반 저장장치(NAS) 2', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 전산실'), '내용연수 경과, 불용 심의 대기', 'HW 1.0', '2023-01-06', 'NAS OS', '7.2.2', '2024-07-24', 'DEFENSE', '2023-01-06', '2023년 부대 네트워크 고도화 사업', NULL, 'DISPOSAL_PENDING', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1065, '백업 장치 1호', 10, 'BK-24', 'BK2-17-00001', '부대 네트워크 기반체계', '3중대 서버반 백업 장치 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 전산실'), '키보드·마우스 교체(2025)', 'Rev.A', '2017-08-11', '펌웨어', '4.0.1', '2019-09-16', 'DEFENSE', '2017-08-11', '2017년 부대 네트워크 고도화 사업', '2026년 서버·네트워크 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1066, '무정전 전원장치 1호', 10, 'UPS-3K', 'UP3-16-00001', '전원체계', '3중대 서버반 무정전 전원장치 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 통신실'), NULL, 'Rev.A', '2016-07-24', NULL, NULL, NULL, NULL, '2016-07-24', '2016년 전원 장비 교체 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1067, '무정전 전원장치 2호', 10, 'UPS-3K', 'UP3-19-00002', '전원체계', '3중대 서버반 무정전 전원장치 2', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 통신실'), NULL, '메인보드 1.3', '2019-06-25', NULL, NULL, NULL, NULL, '2019-06-25', '2019년 전원 장비 교체 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1068, '무정전 전원장치 3호', 10, 'UPS-3K', 'UP3-19-00003', '전원체계', '3중대 서버반 무정전 전원장치 3', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 전산실'), '내용연수 경과로 불용', 'HW 1.0', '2019-04-16', NULL, NULL, NULL, NULL, '2019-04-16', '2019년 전원 장비 교체 사업', NULL, 'DISPOSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1069, '무정전 전원장치 4호', 10, 'UPS-3K', 'UP3-25-00004', '전원체계', '3중대 서버반 무정전 전원장치 4', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 통신실'), '화면 출력 안 됨', 'Rev.A', '2025-01-27', NULL, NULL, NULL, NULL, '2025-01-27', '2025년 전원 장비 교체 사업', NULL, 'BROKEN', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1070, 'CCTV 녹화기 1호', 10, 'NVR-16', 'NV16-22-00001', '영상감시체계', '3중대 서버반 CCTV 녹화기 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 본부중대 정보통신상황실'), NULL, 'HW 1.0', '2022-05-25', '펌웨어', '4.30.2', '2023-01-07', 'ISOLATED', '2022-05-25', '2022년 영상감시 체계 구축 사업', '2026년 영상감시 장비 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1071, '행정용 PC 8호', 10, 'DT-7400', 'DT7-15-00008', '사무자동화체계', '3중대 서버반 행정용 PC 8', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 3중대 서버반 사무실'), '내용연수 경과, 불용 심의 대기', 'Rev.C', '2015-10-29', 'Windows 11 Pro', '24H2', '2018-04-13', 'DEFENSE', '2015-10-29', '2015년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'DISPOSAL_PENDING', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1072, '행정용 PC 9호', 1, 'DT-7400', 'DT7-19-00009', '사무자동화체계', '정보통신대대 행정용 PC 9', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 대대장실'), '토너 교체 주기 확인', 'Rev.B', '2019-05-16', 'Windows 11 Pro', '24H2', '2020-09-15', 'DEFENSE', '2019-05-16', '2019년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1073, '일반전화기 6호', 1, 'TP-200', 'TP2-15-00006', '군 전화체계', '정보통신대대 일반전화기 6', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 대대장실'), NULL, 'HW 1.0', '2015-08-21', NULL, NULL, NULL, NULL, '2015-08-21', '2015년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1074, '행정용 PC 10호', 11, 'DT-7400', 'DT7-19-00010', '사무자동화체계', '근무지원대대 행정용 PC 10', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 대대장실'), '외부 정비 입고, 부품 수급 대기', 'HW 1.0', '2019-07-19', 'Windows 11 Pro', '24H2', '2021-03-11', 'DEFENSE', '2019-07-19', '2019년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'MAINTENANCE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1075, '일반전화기 7호', 11, 'TP-200', 'TP2-21-00007', '군 전화체계', '근무지원대대 일반전화기 7', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 대대장실'), NULL, 'HW 1.0', '2021-09-21', NULL, NULL, NULL, NULL, '2021-09-21', '2021년 군 전화망 개선 사업', NULL, 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1076, '행정용 PC 11호', 2, 'DT-7400', 'DT7-17-00011', '사무자동화체계', '1중대 행정용 PC 11', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 중대본부'), NULL, 'Rev.B', '2017-09-14', 'Windows 11 Pro', '24H2', '2019-04-23', 'DEFENSE', '2017-09-14', '2017년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1077, '복합기 1호', 2, 'MF-6500', 'MF6-25-00001', '사무자동화체계', '1중대 복합기 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 중대본부'), NULL, 'HW 1.0', '2025-10-04', '펌웨어', '5.0.4', '2026-09-30', 'DEFENSE', '2025-10-04', '2025년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1078, '일반전화기 8호', 2, 'TP-200', 'TP2-24-00008', '군 전화체계', '1중대 일반전화기 8', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 중대본부'), NULL, 'Rev.C', '2024-06-07', NULL, NULL, NULL, NULL, '2024-06-07', '2024년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1079, '행정용 PC 12호', 3, 'DT-7400', 'DT7-20-00012', '사무자동화체계', '2중대 행정용 PC 12', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 2중대 중대본부'), NULL, '메인보드 1.3', '2020-04-25', 'Windows 11 Pro', '24H2', '2021-08-04', 'DEFENSE', '2020-04-25', '2020년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1080, '복합기 2호', 3, 'MF-6500', 'MF6-19-00002', '사무자동화체계', '2중대 복합기 2', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 2중대 중대본부'), '전원 불량, 수리 의뢰 예정', 'Rev.A', '2019-11-26', '펌웨어', '5.0.4', '2022-03-17', 'DEFENSE', '2019-11-26', '2019년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'BROKEN', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1081, '일반전화기 9호', 3, 'TP-200', 'TP2-20-00009', '군 전화체계', '2중대 일반전화기 9', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 2중대 중대본부'), NULL, '메인보드 1.3', '2020-02-02', NULL, NULL, NULL, NULL, '2020-02-02', '2020년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1082, '행정용 PC 13호', 4, 'DT-7400', 'DT7-24-00013', '사무자동화체계', '3중대 행정용 PC 13', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 3중대 중대본부'), NULL, 'HW 2.1', '2024-04-05', 'Windows 11 Pro', '24H2', '2026-04-08', 'DEFENSE', '2024-04-05', '2024년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1083, '복합기 3호', 4, 'MF-6500', 'MF6-23-00003', '사무자동화체계', '3중대 복합기 3', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 3중대 중대본부'), NULL, '메인보드 1.3', '2023-02-06', '펌웨어', '5.0.4', '2024-05-30', 'DEFENSE', '2023-02-06', '2023년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1084, '일반전화기 10호', 4, 'TP-200', 'TP2-17-00010', '군 전화체계', '3중대 일반전화기 10', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 3중대 중대본부'), '내용연수 경과, 불용 심의 대기', 'Rev.A', '2017-10-27', NULL, NULL, NULL, NULL, '2017-10-27', '2017년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'DISPOSAL_PENDING', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1085, '행정용 PC 14호', 21, 'DT-7400', 'DT7-22-00014', '사무자동화체계', '본부중대 행정용 PC 14', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 본부중대 중대본부'), '케이블 정리 완료', 'Rev.C', '2022-11-11', 'Windows 11 Pro', '24H2', '2023-06-26', 'DEFENSE', '2022-11-11', '2022년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1086, '복합기 4호', 21, 'MF-6500', 'MF6-21-00004', '사무자동화체계', '본부중대 복합기 4', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 본부중대 중대본부'), NULL, 'Rev.A', '2021-03-09', '펌웨어', '5.0.4', '2021-07-19', 'DEFENSE', '2021-03-09', '2021년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1087, '일반전화기 11호', 21, 'TP-200', 'TP2-24-00011', '군 전화체계', '본부중대 일반전화기 11', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 본부중대 중대본부'), NULL, 'Rev.C', '2024-08-25', NULL, NULL, NULL, NULL, '2024-08-25', '2024년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1088, '행정용 PC 15호', 12, 'DT-7400', 'DT7-22-00015', '사무자동화체계', '본부중대 행정용 PC 15', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 본부중대 중대본부'), NULL, '메인보드 1.3', '2022-07-08', 'Windows 11 Pro', '24H2', '2024-07-28', 'DEFENSE', '2022-07-08', '2022년 사무자동화 장비 보강 사업', NULL, 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1089, '복합기 5호', 12, 'MF-6500', 'MF6-25-00005', '사무자동화체계', '본부중대 복합기 5', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 본부중대 중대본부'), NULL, 'HW 2.1', '2025-02-07', '펌웨어', '5.0.4', '2025-07-12', 'DEFENSE', '2025-02-07', '2025년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1090, '일반전화기 12호', 12, 'TP-200', 'TP2-19-00012', '군 전화체계', '본부중대 일반전화기 12', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 본부중대 중대본부'), '정기 점검 대상', 'HW 1.0', '2019-05-09', NULL, NULL, NULL, NULL, '2019-05-09', '2019년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1091, '행정용 PC 16호', 13, 'DT-7400', 'DT7-15-00016', '사무자동화체계', '보급수송중대 행정용 PC 16', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 중대본부'), NULL, '메인보드 1.3', '2015-05-26', 'Windows 11 Pro', '24H2', '2016-01-25', 'DEFENSE', '2015-05-26', '2015년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1092, '복합기 6호', 13, 'MF-6500', 'MF6-16-00006', '사무자동화체계', '보급수송중대 복합기 6', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 중대본부'), '화면 출력 안 됨', 'Rev.B', '2016-09-14', '펌웨어', '5.0.4', '2018-08-11', 'DEFENSE', '2016-09-14', '2016년 사무자동화 장비 보강 사업', NULL, 'BROKEN', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1093, '일반전화기 13호', 13, 'TP-200', 'TP2-23-00013', '군 전화체계', '보급수송중대 일반전화기 13', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 중대본부'), NULL, '메인보드 1.3', '2023-09-18', NULL, NULL, NULL, NULL, '2023-09-18', '2023년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1094, '행정용 PC 17호', 14, 'DT-7400', 'DT7-19-00017', '사무자동화체계', '복지중대 행정용 PC 17', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 중대본부'), '모니터 2대 연결', 'Rev.A', '2019-07-18', 'Windows 11 Pro', '24H2', '2020-09-19', 'DEFENSE', '2019-07-18', '2019년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1095, '복합기 7호', 14, 'MF-6500', 'MF6-19-00007', '사무자동화체계', '복지중대 복합기 7', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 중대본부'), NULL, '메인보드 1.3', '2019-12-07', '펌웨어', '5.0.4', '2022-04-11', 'DEFENSE', '2019-12-07', '2019년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1096, '일반전화기 14호', 14, 'TP-200', 'TP2-19-00014', '군 전화체계', '복지중대 일반전화기 14', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 중대본부'), '정기 정비 입고', 'Rev.A', '2019-01-07', NULL, NULL, NULL, NULL, '2019-01-07', '2019년 군 전화망 개선 사업', NULL, 'MAINTENANCE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1097, '행정용 PC 18호', 23, 'DT-7400', 'DT7-22-00018', '사무자동화체계', '의무중대 행정용 PC 18', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 의무중대 중대본부'), NULL, 'Rev.A', '2022-09-03', 'Windows 11 Pro', '24H2', '2023-07-25', 'DEFENSE', '2022-09-03', '2022년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1098, '복합기 8호', 23, 'MF-6500', 'MF6-16-00008', '사무자동화체계', '의무중대 복합기 8', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 의무중대 중대본부'), '송신 불량', 'Rev.B', '2016-08-21', '펌웨어', '5.0.4', '2017-03-04', 'DEFENSE', '2016-08-21', '2016년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'BROKEN', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1099, '일반전화기 15호', 23, 'TP-200', 'TP2-16-00015', '군 전화체계', '의무중대 일반전화기 15', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 의무중대 중대본부'), NULL, 'HW 2.1', '2016-07-31', NULL, NULL, NULL, NULL, '2016-07-31', '2016년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1100, '행정용 PC 19호', 15, 'DT-7400', 'DT7-21-00019', '사무자동화체계', '본부중대 행정반 행정용 PC 19', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 본부중대 행정반 사무실'), '예비품으로 보관', 'HW 1.0', '2021-11-08', 'Windows 11 Pro', '24H2', '2024-02-12', 'DEFENSE', '2021-11-08', '2021년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1101, '일반전화기 16호', 15, 'TP-200', 'TP2-18-00016', '군 전화체계', '본부중대 행정반 일반전화기 16', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 본부중대 행정반 사무실'), '화면 출력 안 됨', 'Rev.B', '2018-04-12', NULL, NULL, NULL, NULL, '2018-04-12', '2018년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'BROKEN', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1102, '레이저 프린터 2호', 15, 'PR-M400', 'PRM-23-00002', '사무자동화체계', '본부중대 행정반 레이저 프린터 2', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 본부중대 행정반 사무실'), NULL, '메인보드 1.3', '2023-07-12', '펌웨어', '3.2.1', '2024-11-21', 'DEFENSE', '2023-07-12', '2023년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1103, '행정용 PC 20호', 16, 'DT-7400', 'DT7-21-00020', '사무자동화체계', '본부중대 인사교육반 행정용 PC 20', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 본부중대 인사교육반 사무실'), '키보드·마우스 교체(2025)', 'HW 1.0', '2021-03-28', 'Windows 11 Pro', '24H2', '2023-02-18', 'DEFENSE', '2021-03-28', '2021년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1104, '일반전화기 17호', 16, 'TP-200', 'TP2-16-00017', '군 전화체계', '본부중대 인사교육반 일반전화기 17', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 본부중대 인사교육반 사무실'), '키보드·마우스 교체(2025)', 'HW 2.1', '2016-01-24', NULL, NULL, NULL, NULL, '2016-01-24', '2016년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1105, '레이저 프린터 3호', 16, 'PR-M400', 'PRM-21-00003', '사무자동화체계', '본부중대 인사교육반 레이저 프린터 3', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 본부중대 인사교육반 사무실'), NULL, 'Rev.A', '2021-09-05', '펌웨어', '3.2.1', '2022-02-24', 'DEFENSE', '2021-09-05', '2021년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1106, '행정용 PC 21호', 17, 'DT-7400', 'DT7-24-00021', '사무자동화체계', '보급수송중대 보급반 행정용 PC 21', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 보급반 사무실'), '정기 점검 대상', 'Rev.B', '2024-11-26', 'Windows 11 Pro', '24H2', '2026-04-20', 'DEFENSE', '2024-11-26', '2024년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1107, '일반전화기 18호', 17, 'TP-200', 'TP2-20-00018', '군 전화체계', '보급수송중대 보급반 일반전화기 18', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 보급반 사무실'), '내용연수 경과로 불용', 'Rev.A', '2020-11-19', NULL, NULL, NULL, NULL, '2020-11-19', '2020년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'DISPOSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1108, '레이저 프린터 4호', 17, 'PR-M400', 'PRM-25-00004', '사무자동화체계', '보급수송중대 보급반 레이저 프린터 4', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 보급반 사무실'), NULL, 'Rev.C', '2025-09-01', '펌웨어', '3.2.1', '2026-09-30', 'DEFENSE', '2025-09-01', '2025년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1109, '행정용 PC 22호', 18, 'DT-7400', 'DT7-17-00022', '사무자동화체계', '보급수송중대 수송반 행정용 PC 22', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 수송반 사무실'), '수리 비용 과다, 불용 심의 대기', 'Rev.A', '2017-09-27', 'Windows 11 Pro', '24H2', '2017-10-31', 'DEFENSE', '2017-09-27', '2017년 사무자동화 장비 보강 사업', NULL, 'DISPOSAL_PENDING', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1110, '일반전화기 19호', 18, 'TP-200', 'TP2-15-00019', '군 전화체계', '보급수송중대 수송반 일반전화기 19', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 수송반 사무실'), '전원 불량, 수리 의뢰 예정', 'Rev.B', '2015-11-25', NULL, NULL, NULL, NULL, '2015-11-25', '2015년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'BROKEN', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1111, '행정용 PC 23호', 19, 'DT-7400', 'DT7-24-00023', '사무자동화체계', '복지중대 복지반 행정용 PC 23', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 복지반 사무실'), NULL, 'HW 1.0', '2024-04-14', 'Windows 11 Pro', '24H2', '2025-07-18', 'DEFENSE', '2024-04-14', '2024년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1112, '일반전화기 20호', 19, 'TP-200', 'TP2-22-00020', '군 전화체계', '복지중대 복지반 일반전화기 20', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 복지반 사무실'), NULL, 'Rev.A', '2022-10-05', NULL, NULL, NULL, NULL, '2022-10-05', '2022년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'IN_USE', 'OUT', 20, '대대 강당', '행사 지원 대여', (SELECT MIN(a.id) FROM account a JOIN personnel p ON p.id = a.personnel_id WHERE p.department_id = 19 AND a.active), TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 1 DAY, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 1 DAY
UNION ALL SELECT 1113, '레이저 프린터 5호', 19, 'PR-M400', 'PRM-16-00005', '사무자동화체계', '복지중대 복지반 레이저 프린터 5', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 복지반 사무실'), '송신 불량', 'Rev.A', '2016-07-14', '펌웨어', '3.2.1', '2018-07-20', 'DEFENSE', '2016-07-14', '2016년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'BROKEN', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1114, '행정용 PC 24호', 20, 'DT-7400', 'DT7-22-00024', '사무자동화체계', '복지중대 체육문화반 행정용 PC 24', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 체육문화반 사무실'), NULL, 'Rev.B', '2022-04-18', 'Windows 11 Pro', '24H2', '2023-08-07', 'DEFENSE', '2022-04-18', '2022년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1115, '일반전화기 21호', 20, 'TP-200', 'TP2-24-00021', '군 전화체계', '복지중대 체육문화반 일반전화기 21', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 체육문화반 사무실'), '케이블 정리 완료', 'Rev.B', '2024-09-30', NULL, NULL, NULL, NULL, '2024-09-30', '2024년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1116, '행정용 PC 25호', 24, 'DT-7400', 'DT7-16-00025', '사무자동화체계', '의무중대 의무행정반 행정용 PC 25', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 의무중대 의무행정반 사무실'), '배터리 교체 필요', 'Rev.A', '2016-11-29', 'Windows 11 Pro', '24H2', '2018-08-06', 'DEFENSE', '2016-11-29', '2016년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1117, '일반전화기 22호', 24, 'TP-200', 'TP2-15-00022', '군 전화체계', '의무중대 의무행정반 일반전화기 22', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 의무중대 의무행정반 사무실'), NULL, 'HW 2.1', '2015-04-10', NULL, NULL, NULL, NULL, '2015-04-10', '2015년 군 전화망 개선 사업', NULL, 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1118, '레이저 프린터 6호', 24, 'PR-M400', 'PRM-15-00006', '사무자동화체계', '의무중대 의무행정반 레이저 프린터 6', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 의무중대 의무행정반 사무실'), NULL, 'Rev.C', '2015-02-23', '펌웨어', '3.3.0', '2017-08-05', 'DEFENSE', '2015-02-23', '2015년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1119, '행정용 PC 26호', 25, 'DT-7400', 'DT7-21-00026', '사무자동화체계', '의무중대 진료반 행정용 PC 26', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 의무중대 진료반 사무실'), NULL, 'HW 1.0', '2021-10-13', 'Windows 11 Pro', '24H2', '2022-04-04', 'DEFENSE', '2021-10-13', '2021년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1120, '일반전화기 23호', 25, 'TP-200', 'TP2-17-00023', '군 전화체계', '의무중대 진료반 일반전화기 23', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 의무중대 진료반 사무실'), NULL, 'Rev.B', '2017-01-14', NULL, NULL, NULL, NULL, '2017-01-14', '2017년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1121, '레이저 프린터 7호', 25, 'PR-M400', 'PRM-21-00007', '사무자동화체계', '의무중대 진료반 레이저 프린터 7', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 의무중대 진료반 사무실'), NULL, 'HW 1.0', '2021-03-04', '펌웨어', '3.2.1', '2022-10-27', 'DEFENSE', '2021-03-04', '2021년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1122, '행정용 PC 27호', 25, 'DT-7400', 'DT7-16-00027', '사무자동화체계', '의무중대 진료반 행정용 PC 27', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 의무중대 진료실'), NULL, 'HW 2.1', '2016-09-05', 'Windows 11 Pro', '23H2', '2018-10-03', 'DEFENSE', '2016-09-05', '2016년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1123, '라벨 프린터 1호', 25, 'LP-40', 'LP4-18-00001', '사무자동화체계', '의무중대 진료반 라벨 프린터 1', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 의무중대 진료실'), NULL, 'Rev.C', '2018-04-25', NULL, NULL, NULL, NULL, '2018-04-25', '2018년 사무자동화 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1124, '차량용 무전기 3호', 18, 'VR-50', 'V5-24-00003', '전술무전체계', '보급수송중대 수송반 차량용 무전기 3', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 차량 대기실'), NULL, 'Rev.A', '2024-08-14', '펌웨어', '1.9.3', '2026-04-11', 'BATTLEFIELD', '2024-08-14', '2024년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1125, '차량용 무전기 4호', 18, 'VR-50', 'V5-25-00004', '전술무전체계', '보급수송중대 수송반 차량용 무전기 4', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 차량 대기실'), '키보드·마우스 교체(2025)', 'Rev.B', '2025-06-10', '펌웨어', '1.9.3', '2026-09-30', 'BATTLEFIELD', '2025-06-10', '2025년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1126, '차량용 무전기 5호', 18, 'VR-50', 'V5-18-00005', '전술무전체계', '보급수송중대 수송반 차량용 무전기 5', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 차량 대기실'), NULL, 'Rev.C', '2018-01-21', '펌웨어', '1.9.3', '2019-06-25', 'BATTLEFIELD', '2018-01-21', '2018년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1127, '차량용 무전기 6호', 18, 'VR-50', 'V5-16-00006', '전술무전체계', '보급수송중대 수송반 차량용 무전기 6', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 차량 대기실'), NULL, 'HW 1.0', '2016-10-04', '펌웨어', '1.9.3', '2018-10-10', 'BATTLEFIELD', '2016-10-04', '2016년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1128, '휴대용 무전기 13호', 17, 'RT-200', 'R2-24-00013', '전술무전체계', '보급수송중대 보급반 휴대용 무전기 13', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 보급창고'), NULL, 'Rev.C', '2024-08-20', '펌웨어', '2.4.0', '2025-11-15', 'BATTLEFIELD', '2024-08-20', '2024년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1129, '휴대용 무전기 14호', 17, 'RT-200', 'R2-19-00014', '전술무전체계', '보급수송중대 보급반 휴대용 무전기 14', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 보급수송중대 보급창고'), NULL, 'Rev.A', '2019-07-11', '펌웨어', '2.4.0', '2021-07-14', 'BATTLEFIELD', '2019-07-11', '2019년 전술통신 장비 도입 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1130, '공용 PC 1호', 20, 'DT-5200', 'DT5-25-00001', '사무자동화체계', '복지중대 체육문화반 공용 PC 1', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 사이버지식정보방'), NULL, 'Rev.C', '2025-06-06', 'Windows 11 Pro', '24H2', '2025-11-18', 'DEFENSE', '2025-06-06', '2025년 복지시설 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1131, '공용 PC 2호', 20, 'DT-5200', 'DT5-23-00002', '사무자동화체계', '복지중대 체육문화반 공용 PC 2', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 사이버지식정보방'), NULL, 'Rev.B', '2023-04-22', 'Windows 11 Pro', '24H2', '2024-11-29', 'DEFENSE', '2023-04-22', '2023년 복지시설 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1132, '공용 PC 3호', 20, 'DT-5200', 'DT5-16-00003', '사무자동화체계', '복지중대 체육문화반 공용 PC 3', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 사이버지식정보방'), NULL, 'Rev.B', '2016-10-23', 'Windows 11 Pro', '24H2', '2017-03-14', 'DEFENSE', '2016-10-23', '2016년 복지시설 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'OUT', NULL, '야외 훈련장', '야외 훈련장 설치', (SELECT MIN(a.id) FROM account a JOIN personnel p ON p.id = a.personnel_id WHERE p.department_id = 20 AND a.active), TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 17 DAY, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 17 DAY
UNION ALL SELECT 1133, '공용 PC 4호', 20, 'DT-5200', 'DT5-20-00004', '사무자동화체계', '복지중대 체육문화반 공용 PC 4', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 사이버지식정보방'), NULL, 'HW 2.1', '2020-05-04', 'Windows 11 Pro', '24H2', '2020-12-10', 'DEFENSE', '2020-05-04', '2020년 복지시설 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1134, '공용 PC 5호', 20, 'DT-5200', 'DT5-21-00005', '사무자동화체계', '복지중대 체육문화반 공용 PC 5', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 사이버지식정보방'), NULL, 'Rev.B', '2021-12-21', 'Windows 11 Pro', '24H2', '2022-09-07', 'DEFENSE', '2021-12-21', '2021년 복지시설 장비 보강 사업', NULL, 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1135, '공용 PC 6호', 20, 'DT-5200', 'DT5-20-00006', '사무자동화체계', '복지중대 체육문화반 공용 PC 6', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 사이버지식정보방'), NULL, 'HW 2.1', '2020-06-14', 'Windows 11 Pro', '24H2', '2021-09-02', 'DEFENSE', '2020-06-14', '2020년 복지시설 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1136, '일반전화기 24호', 19, 'TP-200', 'TP2-19-00024', '군 전화체계', '복지중대 복지반 일반전화기 24', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 복지회관'), NULL, 'HW 2.1', '2019-05-07', NULL, NULL, NULL, NULL, '2019-05-07', '2019년 군 전화망 개선 사업', '2026년 군 전화체계 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1137, '행정용 PC 28호', 19, 'DT-7400', 'DT7-17-00028', '사무자동화체계', '복지중대 복지반 행정용 PC 28', (SELECT l.id FROM location l WHERE l.active_name = '근무지원대대 복지중대 복지회관'), NULL, 'Rev.B', '2017-12-26', 'Windows 11 Pro', '24H2', '2019-03-06', 'DEFENSE', '2017-12-26', '2017년 사무자동화 장비 보강 사업', '2026년 정보통신장비 통합 유지보수', 'IN_USE', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1138, '전술 태블릿 1호', 6, 'TB-10', 'TB1-16-00001', '전장정보 단말체계', '1중대 무선반 전술 태블릿 1', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 통신창고'), NULL, 'HW 1.0', '2016-03-19', 'Android', '14', '2016-06-17', 'BATTLEFIELD', '2016-03-19', '2016년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'UNUSED', 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 7 DAY
UNION ALL SELECT 1139, '전술 태블릿 2호', 6, 'TB-10', 'TB1-15-00002', '전장정보 단말체계', '1중대 무선반 전술 태블릿 2', (SELECT l.id FROM location l WHERE l.active_name = '정보통신대대 1중대 통신창고'), NULL, 'Rev.B', '2015-03-27', 'Android', '13', '2016-06-25', 'BATTLEFIELD', '2015-03-27', '2015년 전술통신 장비 도입 사업', '2026년 전술통신 장비 정비 지원', 'IN_USE', 'OUT', 5, NULL, '타부서 대여: 훈련 지원', (SELECT MIN(a.id) FROM account a JOIN personnel p ON p.id = a.personnel_id WHERE p.department_id = 6 AND a.active), TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 1 DAY, TIMESTAMP('2026-11-19 09:00:00') - INTERVAL 1 DAY
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
    ROW(1002, 0, '오피스 2024', '1.2.0', '2024-10-20', 'DT-7400', 'DT7-23-00001', '사무자동화체계'),
    ROW(1002, 1, '백신 에이전트', '5.0.12', '2024-10-05', 'DT-7400', 'DT7-23-00001', '사무자동화체계'),
    ROW(1002, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2024-11-12', 'DT-7400', 'DT7-23-00001', '사무자동화체계'),
    ROW(1002, 3, 'PDF 뷰어', '11.2', '2024-10-16', 'DT-7400', 'DT7-23-00001', '사무자동화체계'),
    ROW(1002, 4, '원격 관리 에이전트', '2.8.0', '2024-10-09', 'DT-7400', 'DT7-23-00001', '사무자동화체계'),
    ROW(1003, 0, '오피스 2024', '1.2.0', '2018-05-19', 'DT-7400', 'DT7-16-00002', '사무자동화체계'),
    ROW(1003, 1, '백신 에이전트', '5.0.12', '2018-05-26', 'DT-7400', 'DT7-16-00002', '사무자동화체계'),
    ROW(1003, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2018-04-11', 'DT-7400', 'DT7-16-00002', '사무자동화체계'),
    ROW(1004, 0, '작전 지도', '3.8.0', '2019-11-14', 'BT-12', 'BT1-18-00001', '전장정보 단말체계'),
    ROW(1007, 0, '교환 관리 프로그램', '6.2.0', '2026-08-29', 'PBX-600', 'PBX-24-00001', '군 전화체계'),
    ROW(1008, 0, '교환 관리 프로그램', '6.2.0', '2021-02-22', 'PBX-600', 'PBX-19-00002', '군 전화체계'),
    ROW(1013, 0, '오피스 2024', '1.2.0', '2024-12-28', 'DT-7400', 'DT7-23-00003', '사무자동화체계'),
    ROW(1013, 1, '백신 에이전트', '5.0.12', '2025-02-04', 'DT-7400', 'DT7-23-00003', '사무자동화체계'),
    ROW(1013, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2025-02-03', 'DT-7400', 'DT7-23-00003', '사무자동화체계'),
    ROW(1018, 0, '녹음 관리 프로그램', '2.0.6', '2024-04-06', 'CR-16', 'CR1-23-00001', '녹음체계'),
    ROW(1021, 0, '오피스 2024', '1.2.0', '2017-02-07', 'DT-7400', 'DT7-16-00004', '사무자동화체계'),
    ROW(1021, 1, '백신 에이전트', '5.0.12', '2017-02-15', 'DT-7400', 'DT7-16-00004', '사무자동화체계'),
    ROW(1022, 0, '작전 지도', '3.8.0', '2017-06-29', 'BT-12', 'BT1-17-00002', '전장정보 단말체계'),
    ROW(1023, 0, '작전 지도', '3.8.0', '2017-04-08', 'BT-12', 'BT1-15-00003', '전장정보 단말체계'),
    ROW(1024, 0, '오피스 2024', '1.2.0', '2024-04-28', 'DT-7400', 'DT7-23-00005', '사무자동화체계'),
    ROW(1024, 1, '백신 에이전트', '5.0.12', '2024-04-19', 'DT-7400', 'DT7-23-00005', '사무자동화체계'),
    ROW(1024, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2024-03-31', 'DT-7400', 'DT7-23-00005', '사무자동화체계'),
    ROW(1024, 3, 'PDF 뷰어', '11.2', '2024-03-29', 'DT-7400', 'DT7-23-00005', '사무자동화체계'),
    ROW(1024, 4, '원격 관리 에이전트', '2.8.0', '2024-03-28', 'DT-7400', 'DT7-23-00005', '사무자동화체계'),
    ROW(1025, 0, '오피스 2024', '1.2.0', '2025-09-13', 'DT-7400', 'DT7-25-00006', '사무자동화체계'),
    ROW(1025, 1, '백신 에이전트', '5.0.12', '2025-10-30', 'DT-7400', 'DT7-25-00006', '사무자동화체계'),
    ROW(1025, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2025-10-27', 'DT-7400', 'DT7-25-00006', '사무자동화체계'),
    ROW(1025, 3, 'PDF 뷰어', '11.2', '2025-10-12', 'DT-7400', 'DT7-25-00006', '사무자동화체계'),
    ROW(1028, 0, '오피스 2024', '1.2.0', '2025-10-30', 'NB-15', 'NB5-24-00013', '사무자동화체계'),
    ROW(1028, 1, '백신 에이전트', '5.0.12', '2025-11-22', 'NB-15', 'NB5-24-00013', '사무자동화체계'),
    ROW(1028, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2025-11-23', 'NB-15', 'NB5-24-00013', '사무자동화체계'),
    ROW(1030, 0, '다자간 회의 관리', '6.4.0', '2021-06-08', 'VC-MCU-20', 'MCU-18-00001', '영상회의체계'),
    ROW(1031, 0, '영상회의 클라이언트', '8.1.0', '2023-10-26', 'VC-300', 'VC3-22-00001', '영상회의체계'),
    ROW(1032, 0, '영상회의 클라이언트', '8.1.0', '2018-03-03', 'VC-300', 'VC3-17-00002', '영상회의체계'),
    ROW(1033, 0, '영상회의 클라이언트', '8.1.0', '2021-10-03', 'VC-300', 'VC3-19-00003', '영상회의체계'),
    ROW(1059, 0, '오피스 2024', '1.2.0', '2026-06-28', 'DT-7400', 'DT7-24-00007', '사무자동화체계'),
    ROW(1059, 1, '백신 에이전트', '5.0.12', '2026-06-11', 'DT-7400', 'DT7-24-00007', '사무자동화체계'),
    ROW(1059, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2026-06-11', 'DT-7400', 'DT7-24-00007', '사무자동화체계'),
    ROW(1059, 3, 'PDF 뷰어', '11.2', '2026-07-03', 'DT-7400', 'DT7-24-00007', '사무자동화체계'),
    ROW(1059, 4, '원격 관리 에이전트', '2.8.0', '2026-06-17', 'DT-7400', 'DT7-24-00007', '사무자동화체계'),
    ROW(1060, 0, '웹 서버', '2.4.62', '2025-10-17', 'SV-R520', 'SVR-25-00001', '부대 네트워크 기반체계'),
    ROW(1060, 1, 'DB 서버', '8.0.40', '2025-09-25', 'SV-R520', 'SVR-25-00001', '부대 네트워크 기반체계'),
    ROW(1060, 2, '백업 에이전트', '6.2.0', '2025-09-06', 'SV-R520', 'SVR-25-00001', '부대 네트워크 기반체계'),
    ROW(1060, 3, '서버 백신', '4.3.1', '2025-09-20', 'SV-R520', 'SVR-25-00001', '부대 네트워크 기반체계'),
    ROW(1061, 0, '웹 서버', '2.4.62', '2026-10-09', 'SV-R520', 'SVR-25-00002', '부대 네트워크 기반체계'),
    ROW(1061, 1, 'DB 서버', '8.0.40', '2026-10-11', 'SV-R520', 'SVR-25-00002', '부대 네트워크 기반체계'),
    ROW(1061, 2, '백업 에이전트', '6.2.0', '2026-11-15', 'SV-R520', 'SVR-25-00002', '부대 네트워크 기반체계'),
    ROW(1061, 3, '서버 백신', '4.3.1', '2026-10-27', 'SV-R520', 'SVR-25-00002', '부대 네트워크 기반체계'),
    ROW(1062, 0, '웹 서버', '2.4.62', '2015-12-30', 'SV-R540', 'SVI-15-00001', '부대 네트워크 기반체계'),
    ROW(1062, 1, '포털 프로그램', '3.2.0', '2015-12-05', 'SV-R540', 'SVI-15-00001', '부대 네트워크 기반체계'),
    ROW(1062, 2, '서버 백신', '4.3.1', '2015-12-25', 'SV-R540', 'SVI-15-00001', '부대 네트워크 기반체계'),
    ROW(1063, 0, '백업 관리', '3.0.1', '2024-05-17', 'NAS-8B', 'NS8-22-00001', '부대 네트워크 기반체계'),
    ROW(1064, 0, '백업 관리', '3.0.1', '2024-08-12', 'NAS-8B', 'NS8-23-00002', '부대 네트워크 기반체계'),
    ROW(1065, 0, '백업 관리', '3.0.1', '2019-09-23', 'BK-24', 'BK2-17-00001', '부대 네트워크 기반체계'),
    ROW(1070, 0, '영상 관제 프로그램', '2.9.0', '2023-02-05', 'NVR-16', 'NV16-22-00001', '영상감시체계'),
    ROW(1071, 0, '오피스 2024', '1.2.0', '2018-04-23', 'DT-7400', 'DT7-15-00008', '사무자동화체계'),
    ROW(1071, 1, '백신 에이전트', '5.0.12', '2018-05-31', 'DT-7400', 'DT7-15-00008', '사무자동화체계'),
    ROW(1071, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2018-05-26', 'DT-7400', 'DT7-15-00008', '사무자동화체계'),
    ROW(1071, 3, 'PDF 뷰어', '11.2', '2018-06-10', 'DT-7400', 'DT7-15-00008', '사무자동화체계'),
    ROW(1072, 0, '오피스 2024', '1.2.0', '2020-11-11', 'DT-7400', 'DT7-19-00009', '사무자동화체계'),
    ROW(1072, 1, '백신 에이전트', '5.0.12', '2020-10-02', 'DT-7400', 'DT7-19-00009', '사무자동화체계'),
    ROW(1072, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2020-10-29', 'DT-7400', 'DT7-19-00009', '사무자동화체계'),
    ROW(1072, 3, 'PDF 뷰어', '11.2', '2020-09-22', 'DT-7400', 'DT7-19-00009', '사무자동화체계'),
    ROW(1074, 0, '오피스 2024', '1.2.0', '2021-04-20', 'DT-7400', 'DT7-19-00010', '사무자동화체계'),
    ROW(1074, 1, '백신 에이전트', '5.0.12', '2021-04-16', 'DT-7400', 'DT7-19-00010', '사무자동화체계'),
    ROW(1074, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2021-04-29', 'DT-7400', 'DT7-19-00010', '사무자동화체계'),
    ROW(1076, 0, '오피스 2024', '1.2.0', '2019-04-25', 'DT-7400', 'DT7-17-00011', '사무자동화체계'),
    ROW(1076, 1, '백신 에이전트', '5.0.12', '2019-06-20', 'DT-7400', 'DT7-17-00011', '사무자동화체계'),
    ROW(1076, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2019-05-08', 'DT-7400', 'DT7-17-00011', '사무자동화체계'),
    ROW(1079, 0, '오피스 2024', '1.2.0', '2021-09-21', 'DT-7400', 'DT7-20-00012', '사무자동화체계'),
    ROW(1079, 1, '백신 에이전트', '5.0.12', '2021-09-06', 'DT-7400', 'DT7-20-00012', '사무자동화체계'),
    ROW(1079, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2021-09-06', 'DT-7400', 'DT7-20-00012', '사무자동화체계'),
    ROW(1082, 0, '오피스 2024', '1.2.0', '2026-05-07', 'DT-7400', 'DT7-24-00013', '사무자동화체계'),
    ROW(1082, 1, '백신 에이전트', '5.0.12', '2026-05-28', 'DT-7400', 'DT7-24-00013', '사무자동화체계'),
    ROW(1082, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2026-05-12', 'DT-7400', 'DT7-24-00013', '사무자동화체계'),
    ROW(1082, 3, 'PDF 뷰어', '11.2', '2026-04-08', 'DT-7400', 'DT7-24-00013', '사무자동화체계'),
    ROW(1082, 4, '원격 관리 에이전트', '2.8.0', '2026-04-16', 'DT-7400', 'DT7-24-00013', '사무자동화체계'),
    ROW(1085, 0, '오피스 2024', '1.2.0', '2023-06-30', 'DT-7400', 'DT7-22-00014', '사무자동화체계'),
    ROW(1085, 1, '백신 에이전트', '5.0.12', '2023-08-03', 'DT-7400', 'DT7-22-00014', '사무자동화체계'),
    ROW(1085, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2023-06-28', 'DT-7400', 'DT7-22-00014', '사무자동화체계'),
    ROW(1085, 3, 'PDF 뷰어', '11.2', '2023-08-24', 'DT-7400', 'DT7-22-00014', '사무자동화체계'),
    ROW(1088, 0, '오피스 2024', '1.2.0', '2024-08-20', 'DT-7400', 'DT7-22-00015', '사무자동화체계'),
    ROW(1088, 1, '백신 에이전트', '5.0.12', '2024-09-25', 'DT-7400', 'DT7-22-00015', '사무자동화체계'),
    ROW(1088, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2024-09-02', 'DT-7400', 'DT7-22-00015', '사무자동화체계'),
    ROW(1091, 0, '오피스 2024', '1.2.0', '2016-03-22', 'DT-7400', 'DT7-15-00016', '사무자동화체계'),
    ROW(1091, 1, '백신 에이전트', '5.0.12', '2016-02-05', 'DT-7400', 'DT7-15-00016', '사무자동화체계'),
    ROW(1091, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2016-03-08', 'DT-7400', 'DT7-15-00016', '사무자동화체계'),
    ROW(1094, 0, '오피스 2024', '1.2.0', '2020-11-17', 'DT-7400', 'DT7-19-00017', '사무자동화체계'),
    ROW(1094, 1, '백신 에이전트', '5.0.12', '2020-10-09', 'DT-7400', 'DT7-19-00017', '사무자동화체계'),
    ROW(1094, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2020-10-31', 'DT-7400', 'DT7-19-00017', '사무자동화체계'),
    ROW(1094, 3, 'PDF 뷰어', '11.2', '2020-10-02', 'DT-7400', 'DT7-19-00017', '사무자동화체계'),
    ROW(1094, 4, '원격 관리 에이전트', '2.8.0', '2020-10-06', 'DT-7400', 'DT7-19-00017', '사무자동화체계'),
    ROW(1097, 0, '오피스 2024', '1.2.0', '2023-09-13', 'DT-7400', 'DT7-22-00018', '사무자동화체계'),
    ROW(1097, 1, '백신 에이전트', '5.0.12', '2023-09-13', 'DT-7400', 'DT7-22-00018', '사무자동화체계'),
    ROW(1097, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2023-09-19', 'DT-7400', 'DT7-22-00018', '사무자동화체계'),
    ROW(1097, 3, 'PDF 뷰어', '11.2', '2023-08-23', 'DT-7400', 'DT7-22-00018', '사무자동화체계'),
    ROW(1097, 4, '원격 관리 에이전트', '2.8.0', '2023-09-17', 'DT-7400', 'DT7-22-00018', '사무자동화체계'),
    ROW(1100, 0, '오피스 2024', '1.2.0', '2024-03-25', 'DT-7400', 'DT7-21-00019', '사무자동화체계'),
    ROW(1100, 1, '백신 에이전트', '5.0.12', '2024-03-18', 'DT-7400', 'DT7-21-00019', '사무자동화체계'),
    ROW(1100, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2024-02-14', 'DT-7400', 'DT7-21-00019', '사무자동화체계'),
    ROW(1100, 3, 'PDF 뷰어', '11.2', '2024-02-18', 'DT-7400', 'DT7-21-00019', '사무자동화체계'),
    ROW(1100, 4, '원격 관리 에이전트', '2.8.0', '2024-03-07', 'DT-7400', 'DT7-21-00019', '사무자동화체계'),
    ROW(1103, 0, '오피스 2024', '1.2.0', '2023-03-06', 'DT-7400', 'DT7-21-00020', '사무자동화체계'),
    ROW(1103, 1, '백신 에이전트', '5.0.12', '2023-04-16', 'DT-7400', 'DT7-21-00020', '사무자동화체계'),
    ROW(1103, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2023-04-16', 'DT-7400', 'DT7-21-00020', '사무자동화체계'),
    ROW(1106, 0, '오피스 2024', '1.2.0', '2026-04-26', 'DT-7400', 'DT7-24-00021', '사무자동화체계'),
    ROW(1106, 1, '백신 에이전트', '5.0.12', '2026-05-13', 'DT-7400', 'DT7-24-00021', '사무자동화체계'),
    ROW(1106, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2026-04-21', 'DT-7400', 'DT7-24-00021', '사무자동화체계'),
    ROW(1106, 3, 'PDF 뷰어', '11.2', '2026-05-31', 'DT-7400', 'DT7-24-00021', '사무자동화체계'),
    ROW(1106, 4, '원격 관리 에이전트', '2.8.0', '2026-05-22', 'DT-7400', 'DT7-24-00021', '사무자동화체계'),
    ROW(1109, 0, '오피스 2024', '1.2.0', '2017-11-20', 'DT-7400', 'DT7-17-00022', '사무자동화체계'),
    ROW(1109, 1, '백신 에이전트', '5.0.12', '2017-12-29', 'DT-7400', 'DT7-17-00022', '사무자동화체계'),
    ROW(1109, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2017-11-15', 'DT-7400', 'DT7-17-00022', '사무자동화체계'),
    ROW(1109, 3, 'PDF 뷰어', '11.2', '2017-11-18', 'DT-7400', 'DT7-17-00022', '사무자동화체계'),
    ROW(1109, 4, '원격 관리 에이전트', '2.8.0', '2017-11-12', 'DT-7400', 'DT7-17-00022', '사무자동화체계'),
    ROW(1111, 0, '오피스 2024', '1.2.0', '2025-08-03', 'DT-7400', 'DT7-24-00023', '사무자동화체계'),
    ROW(1111, 1, '백신 에이전트', '5.0.12', '2025-09-10', 'DT-7400', 'DT7-24-00023', '사무자동화체계'),
    ROW(1111, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2025-08-08', 'DT-7400', 'DT7-24-00023', '사무자동화체계'),
    ROW(1111, 3, 'PDF 뷰어', '11.2', '2025-09-11', 'DT-7400', 'DT7-24-00023', '사무자동화체계'),
    ROW(1114, 0, '오피스 2024', '1.2.0', '2023-09-18', 'DT-7400', 'DT7-22-00024', '사무자동화체계'),
    ROW(1114, 1, '백신 에이전트', '5.0.12', '2023-08-22', 'DT-7400', 'DT7-22-00024', '사무자동화체계'),
    ROW(1114, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2023-08-13', 'DT-7400', 'DT7-22-00024', '사무자동화체계'),
    ROW(1114, 3, 'PDF 뷰어', '11.2', '2023-08-08', 'DT-7400', 'DT7-22-00024', '사무자동화체계'),
    ROW(1116, 0, '오피스 2024', '1.2.0', '2018-09-26', 'DT-7400', 'DT7-16-00025', '사무자동화체계'),
    ROW(1116, 1, '백신 에이전트', '5.0.12', '2018-09-29', 'DT-7400', 'DT7-16-00025', '사무자동화체계'),
    ROW(1116, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2018-09-29', 'DT-7400', 'DT7-16-00025', '사무자동화체계'),
    ROW(1116, 3, 'PDF 뷰어', '11.2', '2018-09-26', 'DT-7400', 'DT7-16-00025', '사무자동화체계'),
    ROW(1119, 0, '오피스 2024', '1.2.0', '2022-04-07', 'DT-7400', 'DT7-21-00026', '사무자동화체계'),
    ROW(1119, 1, '백신 에이전트', '5.0.12', '2022-06-01', 'DT-7400', 'DT7-21-00026', '사무자동화체계'),
    ROW(1119, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2022-04-09', 'DT-7400', 'DT7-21-00026', '사무자동화체계'),
    ROW(1119, 3, 'PDF 뷰어', '11.2', '2022-04-18', 'DT-7400', 'DT7-21-00026', '사무자동화체계'),
    ROW(1122, 0, '오피스 2024', '1.2.0', '2018-10-13', 'DT-7400', 'DT7-16-00027', '사무자동화체계'),
    ROW(1122, 1, '백신 에이전트', '5.0.12', '2018-10-20', 'DT-7400', 'DT7-16-00027', '사무자동화체계'),
    ROW(1122, 2, '문서 보안(DRM) 에이전트', '3.4.1', '2018-10-31', 'DT-7400', 'DT7-16-00027', '사무자동화체계'),
    ROW(1130, 0, '백신 에이전트', '5.0.12', '2025-12-13', 'DT-5200', 'DT5-25-00001', '사무자동화체계'),
    ROW(1130, 1, '문서 보안(DRM) 에이전트', '3.4.1', '2025-12-30', 'DT-5200', 'DT5-25-00001', '사무자동화체계'),
    ROW(1131, 0, '백신 에이전트', '5.0.12', '2024-12-15', 'DT-5200', 'DT5-23-00002', '사무자동화체계'),
    ROW(1131, 1, '문서 보안(DRM) 에이전트', '3.4.1', '2025-01-20', 'DT-5200', 'DT5-23-00002', '사무자동화체계'),
    ROW(1132, 0, '백신 에이전트', '5.0.12', '2017-04-24', 'DT-5200', 'DT5-16-00003', '사무자동화체계'),
    ROW(1132, 1, '문서 보안(DRM) 에이전트', '3.4.1', '2017-03-29', 'DT-5200', 'DT5-16-00003', '사무자동화체계'),
    ROW(1133, 0, '백신 에이전트', '5.0.12', '2021-02-06', 'DT-5200', 'DT5-20-00004', '사무자동화체계'),
    ROW(1133, 1, '문서 보안(DRM) 에이전트', '3.4.1', '2021-01-05', 'DT-5200', 'DT5-20-00004', '사무자동화체계'),
    ROW(1134, 0, '백신 에이전트', '5.0.12', '2022-09-25', 'DT-5200', 'DT5-21-00005', '사무자동화체계'),
    ROW(1134, 1, '문서 보안(DRM) 에이전트', '3.4.1', '2022-09-25', 'DT-5200', 'DT5-21-00005', '사무자동화체계'),
    ROW(1135, 0, '백신 에이전트', '5.0.12', '2021-09-24', 'DT-5200', 'DT5-20-00006', '사무자동화체계'),
    ROW(1135, 1, '문서 보안(DRM) 에이전트', '3.4.1', '2021-09-17', 'DT-5200', 'DT5-20-00006', '사무자동화체계'),
    ROW(1137, 0, '오피스 2024', '1.2.0', '2019-03-16', 'DT-7400', 'DT7-17-00028', '사무자동화체계'),
    ROW(1137, 1, '백신 에이전트', '5.0.12', '2019-04-15', 'DT-7400', 'DT7-17-00028', '사무자동화체계'),
    ROW(1138, 0, '지도 열람 앱', '4.1.0', '2016-06-28', 'TB-10', 'TB1-16-00001', '전장정보 단말체계'),
    ROW(1138, 1, '보안 메신저', '2.3.5', '2016-06-27', 'TB-10', 'TB1-16-00001', '전장정보 단말체계'),
    ROW(1139, 0, '지도 열람 앱', '4.1.0', '2016-07-01', 'TB-10', 'TB1-15-00002', '전장정보 단말체계'),
    ROW(1139, 1, '보안 메신저', '2.3.5', '2016-08-17', 'TB-10', 'TB1-15-00002', '전장정보 단말체계')) v
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
    ROW(21, 0, '업무망', '192.0.2.16', '02-00-00-00-10-07', '020000001007', '020000001007', 'NB-14', 'SN-NB-0007', '사무자동화체계', '020000001007'),
    ROW(24, 0, '업무망', '192.0.2.17', '02-00-00-00-10-08', '020000001008', '020000001008', 'NB-14', 'SN-NB-0008', '사무자동화체계', '020000001008'),
    ROW(27, 0, '업무망', '198.51.100.10', '02-00-00-00-10-09', '020000001009', '020000001009', 'NB-14', 'SN-NB-0009', '사무자동화체계', '020000001009'),
    ROW(30, 0, '업무망', '198.51.100.11', '02-00-00-00-10-0A', '02000000100A', '02000000100A', 'NB-14', 'SN-NB-0010', '사무자동화체계', '02000000100A'),
    ROW(33, 0, '업무망', '198.51.100.12', '02-00-00-00-10-0B', '02000000100B', '02000000100B', 'NB-14', 'SN-NB-0011', '사무자동화체계', '02000000100B'),
    ROW(36, 0, '업무망', '198.51.100.13', '02-00-00-00-10-0C', '02000000100C', '02000000100C', 'NB-14', 'SN-NB-0012', '사무자동화체계', '02000000100C'),
    ROW(1001, 0, '업무망', '192.0.2.18', '02-00-00-00-10-0D', '02000000100D', '02000000100D', 'DS-75', 'DS7-22-00001', '영상표출체계', '02000000100D'),
    ROW(1002, 0, '업무망', '192.0.2.19', '02-00-00-00-10-0E', '02000000100E', '02000000100E', 'DT-7400', 'DT7-23-00001', '사무자동화체계', '02000000100E'),
    ROW(1003, 0, '업무망', '192.0.2.20', '02-00-00-00-10-0F', '02000000100F', '02000000100F', 'DT-7400', 'DT7-16-00002', '사무자동화체계', '02000000100F'),
    ROW(1004, 0, '업무망', '192.0.2.21', '02-00-00-00-10-10', '020000001010', '020000001010', 'BT-12', 'BT1-18-00001', '전장정보 단말체계', '020000001010'),
    ROW(1007, 0, '업무망', '192.0.2.22', '02-00-00-00-10-11', '020000001011', '020000001011', 'PBX-600', 'PBX-24-00001', '군 전화체계', '020000001011'),
    ROW(1008, 0, '업무망', '192.0.2.23', '02-00-00-00-10-12', '020000001012', '020000001012', 'PBX-600', 'PBX-19-00002', '군 전화체계', '020000001012'),
    ROW(1013, 0, '업무망', '192.0.2.24', '02-00-00-00-10-13', '020000001013', NULL, 'DT-7400', 'DT7-23-00003', '사무자동화체계', '020000001013'),
    ROW(1014, 0, '업무망', '192.0.2.25', '02-00-00-00-10-14', '020000001014', '020000001014', 'BR-500', 'BR5-24-00001', '전술무전체계', '020000001014'),
    ROW(1018, 0, '업무망', '192.0.2.26', '02-00-00-00-10-15', '020000001015', '020000001015', 'CR-16', 'CR1-23-00001', '녹음체계', '020000001015'),
    ROW(1021, 0, '업무망', '192.0.2.27', '02-00-00-00-10-16', '020000001016', '020000001016', 'DT-7400', 'DT7-16-00004', '사무자동화체계', '020000001016'),
    ROW(1022, 0, '업무망', '192.0.2.28', '02-00-00-00-10-17', '020000001017', '020000001017', 'BT-12', 'BT1-17-00002', '전장정보 단말체계', '020000001017'),
    ROW(1023, 0, '업무망', '192.0.2.29', '02-00-00-00-10-18', '020000001018', '020000001018', 'BT-12', 'BT1-15-00003', '전장정보 단말체계', '020000001018'),
    ROW(1024, 0, '업무망', '192.0.2.30', '02-00-00-00-10-19', '020000001019', '020000001019', 'DT-7400', 'DT7-23-00005', '사무자동화체계', '020000001019'),
    ROW(1025, 0, '업무망', '192.0.2.31', '02-00-00-00-10-1A', '02000000101A', '02000000101A', 'DT-7400', 'DT7-25-00006', '사무자동화체계', '02000000101A'),
    ROW(1028, 0, '업무망', '192.0.2.32', '02-00-00-00-10-1B', '02000000101B', '02000000101B', 'NB-15', 'NB5-24-00013', '사무자동화체계', '02000000101B'),
    ROW(1029, 0, '업무망', '192.0.2.33', '02-00-00-00-10-1C', '02000000101C', '02000000101C', 'PR-M400', 'PRM-15-00001', '사무자동화체계', '02000000101C'),
    ROW(1030, 0, '업무망', '192.0.2.34', '02-00-00-00-10-1D', '02000000101D', '02000000101D', 'VC-MCU-20', 'MCU-18-00001', '영상회의체계', '02000000101D'),
    ROW(1031, 0, '업무망', '192.0.2.35', '02-00-00-00-10-1E', '02000000101E', '02000000101E', 'VC-300', 'VC3-22-00001', '영상회의체계', '02000000101E'),
    ROW(1032, 0, '업무망', '192.0.2.36', '02-00-00-00-10-1F', '02000000101F', '02000000101F', 'VC-300', 'VC3-17-00002', '영상회의체계', '02000000101F'),
    ROW(1033, 0, '업무망', '192.0.2.37', '02-00-00-00-10-20', '020000001020', '020000001020', 'VC-300', 'VC3-19-00003', '영상회의체계', '020000001020'),
    ROW(1040, 0, '업무망', '192.0.2.38', '02-00-00-00-10-21', '020000001021', '020000001021', 'DS-75', 'DS7-16-00002', '영상표출체계', '020000001021'),
    ROW(1043, 0, '업무망', '192.0.2.39', '02-00-00-00-10-22', '020000001022', '020000001022', 'GR-3000', 'GR3-16-00001', '부대 네트워크 기반체계', '020000001022,020000001023'),
    ROW(1043, 1, '관리용', '192.0.2.40', '02-00-00-00-10-23', '020000001023', '020000001023', 'GR-3000', 'GR3-16-00001', '부대 네트워크 기반체계', '020000001022,020000001023'),
    ROW(1044, 0, '업무망', '192.0.2.41', '02-00-00-00-10-24', '020000001024', '020000001024', 'GR-3000', 'GR3-24-00002', '부대 네트워크 기반체계', '020000001024,020000001025'),
    ROW(1044, 1, '관리용', '192.0.2.42', '02-00-00-00-10-25', '020000001025', '020000001025', 'GR-3000', 'GR3-24-00002', '부대 네트워크 기반체계', '020000001024,020000001025'),
    ROW(1045, 0, '업무망', '192.0.2.43', '02-00-00-00-10-26', '020000001026', '020000001026', 'FW-500', 'FW5-20-00001', '부대 네트워크 기반체계', '020000001026,020000001027'),
    ROW(1045, 1, '관리용', '192.0.2.44', '02-00-00-00-10-27', '020000001027', '020000001027', 'FW-500', 'FW5-20-00001', '부대 네트워크 기반체계', '020000001026,020000001027'),
    ROW(1046, 0, '업무망', '192.0.2.45', '02-00-00-00-10-28', '020000001028', '020000001028', 'FW-500', 'FW5-19-00002', '부대 네트워크 기반체계', '020000001028,020000001029'),
    ROW(1046, 1, '관리용', '192.0.2.46', '02-00-00-00-10-29', '020000001029', '020000001029', 'FW-500', 'FW5-19-00002', '부대 네트워크 기반체계', '020000001028,020000001029'),
    ROW(1047, 0, '업무망', '192.0.2.47', '02-00-00-00-10-2A', '02000000102A', '02000000102A', 'CS-9600', 'CS9-15-00001', '부대 네트워크 기반체계', '02000000102A,02000000102B'),
    ROW(1047, 1, '관리용', '192.0.2.48', '02-00-00-00-10-2B', '02000000102B', '02000000102B', 'CS-9600', 'CS9-15-00001', '부대 네트워크 기반체계', '02000000102A,02000000102B'),
    ROW(1048, 0, '업무망', '192.0.2.49', '02-00-00-00-10-2C', '02000000102C', '02000000102C', 'CS-9600', 'CS9-18-00002', '부대 네트워크 기반체계', '02000000102C,02000000102D'),
    ROW(1048, 1, '관리용', '192.0.2.50', '02-00-00-00-10-2D', '02000000102D', '02000000102D', 'CS-9600', 'CS9-18-00002', '부대 네트워크 기반체계', '02000000102C,02000000102D'),
    ROW(1049, 0, '업무망', '198.51.100.14', '02-00-00-00-10-2E', '02000000102E', '02000000102E', 'SW-2400', 'S24-20-00001', '부대 네트워크 기반체계', '02000000102E'),
    ROW(1050, 0, '업무망', '192.0.2.51', '02-00-00-00-10-2F', '02000000102F', '02000000102F', 'SW-2400', 'S24-18-00002', '부대 네트워크 기반체계', '02000000102F'),
    ROW(1051, 0, '업무망', '192.0.2.52', '02-00-00-00-10-30', '020000001030', '020000001030', 'SW-2400', 'S24-18-00003', '부대 네트워크 기반체계', '020000001030'),
    ROW(1052, 0, '업무망', '192.0.2.53', '02-00-00-00-10-31', '020000001031', '020000001031', 'SW-2400', 'S24-21-00004', '부대 네트워크 기반체계', '020000001031'),
    ROW(1053, 0, '업무망', '192.0.2.54', '02-00-00-00-10-32', '020000001032', '020000001032', 'SW-2400', 'S24-23-00005', '부대 네트워크 기반체계', '020000001032'),
    ROW(1054, 0, '업무망', '198.51.100.15', '02-00-00-00-10-33', '020000001033', '020000001033', 'SW-2400', 'S24-21-00006', '부대 네트워크 기반체계', '020000001033'),
    ROW(1055, 0, '업무망', '198.51.100.16', '02-00-00-00-10-34', '020000001034', '020000001034', 'SW-2400', 'S24-15-00007', '부대 네트워크 기반체계', '020000001034'),
    ROW(1056, 0, '업무망', '198.51.100.17', '02-00-00-00-10-35', '020000001035', '020000001035', 'SW-2400', 'S24-24-00008', '부대 네트워크 기반체계', '020000001035'),
    ROW(1057, 0, '업무망', '198.51.100.18', '02-00-00-00-10-36', '020000001036', '020000001036', 'SW-2400', 'S24-17-00009', '부대 네트워크 기반체계', '020000001036'),
    ROW(1058, 0, '업무망', '198.51.100.19', '02-00-00-00-10-37', '020000001037', '020000001037', 'SW-2400', 'S24-17-00010', '부대 네트워크 기반체계', '020000001037'),
    ROW(1059, 0, '업무망', '192.0.2.55', '02-00-00-00-10-38', '020000001038', '020000001038', 'DT-7400', 'DT7-24-00007', '사무자동화체계', '020000001038'),
    ROW(1060, 0, '업무망', '192.0.2.56', '02-00-00-00-10-39', '020000001039', '020000001039', 'SV-R520', 'SVR-25-00001', '부대 네트워크 기반체계', '020000001039,02000000103A'),
    ROW(1060, 1, '관리용', '192.0.2.57', '02-00-00-00-10-3A', '02000000103A', '02000000103A', 'SV-R520', 'SVR-25-00001', '부대 네트워크 기반체계', '020000001039,02000000103A'),
    ROW(1061, 0, '업무망', '192.0.2.58', '02-00-00-00-10-3B', '02000000103B', '02000000103B', 'SV-R520', 'SVR-25-00002', '부대 네트워크 기반체계', '02000000103B,02000000103C'),
    ROW(1061, 1, '관리용', '192.0.2.59', '02-00-00-00-10-3C', '02000000103C', '02000000103C', 'SV-R520', 'SVR-25-00002', '부대 네트워크 기반체계', '02000000103B,02000000103C'),
    ROW(1062, 0, '업무망', '192.0.2.60', '02-00-00-00-10-3D', '02000000103D', '02000000103D', 'SV-R540', 'SVI-15-00001', '부대 네트워크 기반체계', '02000000103D,02000000103E'),
    ROW(1062, 1, '관리용', '192.0.2.61', '02-00-00-00-10-3E', '02000000103E', '02000000103E', 'SV-R540', 'SVI-15-00001', '부대 네트워크 기반체계', '02000000103D,02000000103E'),
    ROW(1063, 0, '업무망', '192.0.2.62', '02-00-00-00-10-3F', '02000000103F', '02000000103F', 'NAS-8B', 'NS8-22-00001', '부대 네트워크 기반체계', '02000000103F'),
    ROW(1064, 0, '업무망', '192.0.2.63', '02-00-00-00-10-40', '020000001040', '020000001040', 'NAS-8B', 'NS8-23-00002', '부대 네트워크 기반체계', '020000001040'),
    ROW(1065, 0, '업무망', '192.0.2.64', '02-00-00-00-10-41', '020000001041', '020000001041', 'BK-24', 'BK2-17-00001', '부대 네트워크 기반체계', '020000001041'),
    ROW(1070, 0, '업무망', '192.0.2.65', '02-00-00-00-10-42', '020000001042', '020000001042', 'NVR-16', 'NV16-22-00001', '영상감시체계', '020000001042'),
    ROW(1071, 0, '업무망', '192.0.2.66', '02-00-00-00-10-43', '020000001043', '020000001043', 'DT-7400', 'DT7-15-00008', '사무자동화체계', '020000001043'),
    ROW(1072, 0, '업무망', '192.0.2.67', '02-00-00-00-10-44', '020000001044', '020000001044', 'DT-7400', 'DT7-19-00009', '사무자동화체계', '020000001044'),
    ROW(1074, 0, '업무망', '198.51.100.20', '02-00-00-00-10-45', '020000001045', '020000001045', 'DT-7400', 'DT7-19-00010', '사무자동화체계', '020000001045'),
    ROW(1076, 0, '업무망', '192.0.2.68', '02-00-00-00-10-46', '020000001046', '020000001046', 'DT-7400', 'DT7-17-00011', '사무자동화체계', '020000001046'),
    ROW(1077, 0, '업무망', '192.0.2.69', '02-00-00-00-10-47', '020000001047', '020000001047', 'MF-6500', 'MF6-25-00001', '사무자동화체계', '020000001047'),
    ROW(1079, 0, '업무망', '192.0.2.70', '02-00-00-00-10-48', '020000001048', '020000001048', 'DT-7400', 'DT7-20-00012', '사무자동화체계', '020000001048'),
    ROW(1080, 0, '업무망', '192.0.2.71', '02-00-00-00-10-49', '020000001049', '020000001049', 'MF-6500', 'MF6-19-00002', '사무자동화체계', '020000001049'),
    ROW(1082, 0, '업무망', '192.0.2.72', '02-00-00-00-10-4A', '02000000104A', '02000000104A', 'DT-7400', 'DT7-24-00013', '사무자동화체계', '02000000104A'),
    ROW(1083, 0, '업무망', '192.0.2.73', '02-00-00-00-10-4B', '02000000104B', '02000000104B', 'MF-6500', 'MF6-23-00003', '사무자동화체계', '02000000104B'),
    ROW(1085, 0, '업무망', '192.0.2.74', '02-00-00-00-10-4C', '02000000104C', '02000000104C', 'DT-7400', 'DT7-22-00014', '사무자동화체계', '02000000104C'),
    ROW(1086, 0, '업무망', '192.0.2.75', '02-00-00-00-10-4D', '02000000104D', '02000000104D', 'MF-6500', 'MF6-21-00004', '사무자동화체계', '02000000104D'),
    ROW(1088, 0, '업무망', '198.51.100.21', '02-00-00-00-10-4E', '02000000104E', '02000000104E', 'DT-7400', 'DT7-22-00015', '사무자동화체계', '02000000104E'),
    ROW(1089, 0, '업무망', '198.51.100.22', '02-00-00-00-10-4F', '02000000104F', '02000000104F', 'MF-6500', 'MF6-25-00005', '사무자동화체계', '02000000104F'),
    ROW(1091, 0, '업무망', '198.51.100.23', '02-00-00-00-10-50', '020000001050', '020000001050', 'DT-7400', 'DT7-15-00016', '사무자동화체계', '020000001050'),
    ROW(1092, 0, '업무망', '198.51.100.24', '02-00-00-00-10-51', '020000001051', '020000001051', 'MF-6500', 'MF6-16-00006', '사무자동화체계', '020000001051'),
    ROW(1094, 0, '업무망', '198.51.100.25', '02-00-00-00-10-52', '020000001052', '020000001052', 'DT-7400', 'DT7-19-00017', '사무자동화체계', '020000001052'),
    ROW(1095, 0, '업무망', '198.51.100.26', '02-00-00-00-10-53', '020000001053', '020000001053', 'MF-6500', 'MF6-19-00007', '사무자동화체계', '020000001053'),
    ROW(1097, 0, '업무망', '198.51.100.27', '02-00-00-00-10-54', '020000001054', '020000001054', 'DT-7400', 'DT7-22-00018', '사무자동화체계', '020000001054'),
    ROW(1098, 0, '업무망', '198.51.100.28', '02-00-00-00-10-55', '020000001055', '020000001055', 'MF-6500', 'MF6-16-00008', '사무자동화체계', '020000001055'),
    ROW(1100, 0, '업무망', '198.51.100.29', '02-00-00-00-10-56', '020000001056', '020000001056', 'DT-7400', 'DT7-21-00019', '사무자동화체계', '020000001056'),
    ROW(1102, 0, '업무망', '198.51.100.30', '02-00-00-00-10-57', '020000001057', '020000001057', 'PR-M400', 'PRM-23-00002', '사무자동화체계', '020000001057'),
    ROW(1103, 0, '업무망', '198.51.100.31', '02-00-00-00-10-58', '020000001058', '020000001058', 'DT-7400', 'DT7-21-00020', '사무자동화체계', '020000001058'),
    ROW(1105, 0, '업무망', '198.51.100.32', '02-00-00-00-10-59', '020000001059', '020000001059', 'PR-M400', 'PRM-21-00003', '사무자동화체계', '020000001059'),
    ROW(1106, 0, '업무망', '198.51.100.33', '02-00-00-00-10-5A', '02000000105A', '02000000105A', 'DT-7400', 'DT7-24-00021', '사무자동화체계', '02000000105A'),
    ROW(1108, 0, '업무망', '198.51.100.34', '02-00-00-00-10-5B', '02000000105B', '02000000105B', 'PR-M400', 'PRM-25-00004', '사무자동화체계', '02000000105B'),
    ROW(1109, 0, '업무망', '198.51.100.35', '02-00-00-00-10-5C', '02000000105C', '02000000105C', 'DT-7400', 'DT7-17-00022', '사무자동화체계', '02000000105C'),
    ROW(1111, 0, '업무망', '198.51.100.36', '02-00-00-00-10-5D', '02000000105D', '02000000105D', 'DT-7400', 'DT7-24-00023', '사무자동화체계', '02000000105D'),
    ROW(1113, 0, '업무망', '198.51.100.37', '02-00-00-00-10-5E', '02000000105E', '02000000105E', 'PR-M400', 'PRM-16-00005', '사무자동화체계', '02000000105E'),
    ROW(1114, 0, '업무망', '198.51.100.38', '02-00-00-00-10-5F', '02000000105F', '02000000105F', 'DT-7400', 'DT7-22-00024', '사무자동화체계', '02000000105F'),
    ROW(1116, 0, '업무망', '198.51.100.39', '02-00-00-00-10-60', '020000001060', '020000001060', 'DT-7400', 'DT7-16-00025', '사무자동화체계', '020000001060'),
    ROW(1118, 0, '업무망', '198.51.100.40', '02-00-00-00-10-61', '020000001061', '020000001061', 'PR-M400', 'PRM-15-00006', '사무자동화체계', '020000001061'),
    ROW(1119, 0, '업무망', '198.51.100.41', '02-00-00-00-10-62', '020000001062', '020000001062', 'DT-7400', 'DT7-21-00026', '사무자동화체계', '020000001062'),
    ROW(1121, 0, '업무망', '198.51.100.42', '02-00-00-00-10-63', '020000001063', '020000001063', 'PR-M400', 'PRM-21-00007', '사무자동화체계', '020000001063'),
    ROW(1122, 0, '업무망', '198.51.100.43', '02-00-00-00-10-64', '020000001064', '020000001064', 'DT-7400', 'DT7-16-00027', '사무자동화체계', '020000001064'),
    ROW(1130, 0, '업무망', '198.51.100.44', '02-00-00-00-10-65', '020000001065', '020000001065', 'DT-5200', 'DT5-25-00001', '사무자동화체계', '020000001065'),
    ROW(1131, 0, '업무망', '198.51.100.45', '02-00-00-00-10-66', '020000001066', '020000001066', 'DT-5200', 'DT5-23-00002', '사무자동화체계', '020000001066'),
    ROW(1132, 0, '업무망', '198.51.100.46', '02-00-00-00-10-67', '020000001067', '020000001067', 'DT-5200', 'DT5-16-00003', '사무자동화체계', '020000001067'),
    ROW(1133, 0, '업무망', '198.51.100.47', '02-00-00-00-10-68', '020000001068', '020000001068', 'DT-5200', 'DT5-20-00004', '사무자동화체계', '020000001068'),
    ROW(1134, 0, '업무망', '198.51.100.48', '02-00-00-00-10-69', '020000001069', '020000001069', 'DT-5200', 'DT5-21-00005', '사무자동화체계', '020000001069'),
    ROW(1135, 0, '업무망', '198.51.100.49', '02-00-00-00-10-6A', '02000000106A', '02000000106A', 'DT-5200', 'DT5-20-00006', '사무자동화체계', '02000000106A'),
    ROW(1137, 0, '업무망', '198.51.100.50', '02-00-00-00-10-6B', '02000000106B', '02000000106B', 'DT-7400', 'DT7-17-00028', '사무자동화체계', '02000000106B'),
    ROW(1138, 0, '업무망', '192.0.2.76', '02-00-00-00-10-6C', '02000000106C', '02000000106C', 'TB-10', 'TB1-16-00001', '전장정보 단말체계', '02000000106C'),
    ROW(1139, 0, '업무망', '192.0.2.77', '02-00-00-00-10-6D', '02000000106D', '02000000106D', 'TB-10', 'TB1-15-00002', '전장정보 단말체계', '02000000106D')) v
JOIN equipment e ON e.id = v.column_0 AND e.model_name = v.column_7 AND e.serial_number = v.column_8 AND e.system_name = v.column_9
WHERE NOT EXISTS (SELECT 1 FROM equipment_network n WHERE n.equipment_id = v.column_0)
  AND NOT EXISTS (SELECT 1 FROM equipment_network n WHERE n.equipment_id <> v.column_0 AND FIND_IN_SET(n.active_mac_key, v.column_10));

-- 장비 사진·관련자료 (REQ-520·521, slices/22 4.2): 파일 번호 90001~ (시연 업무 시드가 stored_file 최대 번호 < 100001을 보므로 그 아래). 그림·글은 가상

INSERT INTO stored_file (id, purpose, linked, uploaded_by_account_id, original_name, extension, size, sha256, created_at, linked_at)
SELECT v.column_0, v.column_1, TRUE, a.id, v.column_3, v.column_4, v.column_5, v.column_6, v.column_7, v.column_7 FROM (VALUES
    ROW(90001, 'EQUIPMENT_PHOTO', 'bn1-co1-s1-lead', '구내교환기_1호.png', 'png', 231, 'f9a342f7e79a5be22bd07e874d77d1883cb9893e70d6f2a09bfdac1dcefcef81', '2026-11-10 10:20:00', 1007, 'PBX-600', 'PBX-24-00001'),
    ROW(90002, 'EQUIPMENT_PHOTO', 'bn1-co1-s1-lead', '구내교환기_2호.png', 'png', 225, '0a439a23d5bfb7c4f84192b836aecd01b8e682861a29edb8a3c5ef2e41c4e6d2', '2026-11-10 10:25:00', 1008, 'PBX-600', 'PBX-19-00002'),
    ROW(90003, 'EQUIPMENT_PHOTO', 'bn1-co2-s2-lead', '영상회의단말_1호.png', 'png', 279, '85b774d0de06499338b1926f638a7329ebd0bf064e1af06203be174680063e9f', '2026-11-12 14:10:00', 1031, 'VC-300', 'VC3-22-00001'),
    ROW(90004, 'EQUIPMENT_PHOTO', 'bn1-dual', '기지국무전기_1호.png', 'png', 279, '65e97f1fdddd3969b8ae268852d7e5cab44505123bd287f8fafef29e3e373bea', '2026-11-16 15:30:00', 1014, 'BR-500', 'BR5-24-00001'),
    ROW(90005, 'EQUIPMENT_FILE', 'bn1-co1-s1-lead', '구내교환기1호_정기점검기록_2026-11.txt', 'txt', 357, '85d52443d8bc2d2f9b6e1135327a65274f9facecaf43698243087d2dffb10f58', '2026-11-10 16:40:00', 1007, 'PBX-600', 'PBX-24-00001'),
    ROW(90006, 'EQUIPMENT_FILE', 'bn1-co1-s1-lead', '구내교환기1호_단자함_배선.png', 'png', 198, '1b232a7cb9860fad448184fae24241f0219d48c43291f15114d708c51ddb7ef0', '2026-11-10 16:45:00', 1007, 'PBX-600', 'PBX-24-00001')) v
JOIN account a ON a.login_id = v.column_2 AND a.active
JOIN equipment e ON e.id = v.column_8 AND e.model_name = v.column_9 AND e.serial_number = v.column_10
WHERE NOT EXISTS (SELECT 1 FROM stored_file f WHERE f.id = v.column_0)
  AND NOT (v.column_1 = 'EQUIPMENT_PHOTO' AND EXISTS (SELECT 1 FROM equipment_photo p WHERE p.equipment_id = v.column_8))
  AND NOT (v.column_1 = 'EQUIPMENT_FILE' AND EXISTS (SELECT 1 FROM equipment_file x WHERE x.equipment_id = v.column_8));

INSERT INTO stored_file_body (file_id, data)
SELECT v.column_0, v.column_2 FROM (VALUES
    ROW(90001, 'f9a342f7e79a5be22bd07e874d77d1883cb9893e70d6f2a09bfdac1dcefcef81', X'89504E470D0A1A0A0000000D49484452000000A0000000780803000000427C095D00000015504C5445ECF0F3868E96343A4021252940C057FD7E141971C24414EC9E0000008D4944415478DAEDDB310E80200C405114B9FF954D607433C5147C7F627C134D13284592A490CE643D8147AA0001010101016702EBE8A3036014B0F5000101D705EE740F9AC5808080808076923F02AF1E2020E0BA403B89590C080808086816DB490001015D33761240404040403B09A077338080DE6ED949CC624040404040404040C07860F62FE49224BDEA06E89F56FD86F3443A0000000049454E44AE426082'),
    ROW(90002, '0a439a23d5bfb7c4f84192b836aecd01b8e682861a29edb8a3c5ef2e41c4e6d2', X'89504E470D0A1A0A0000000D49484452000000A0000000780803000000427C095D00000015504C5445ECF0F3868E96343A4021252940C057FD7E141971C24414EC9E000000874944415478DAEDDB310E80200C405114B9FF954D607433C5147C7F627C539B0E942249524867B29EC02355808080808080338175F4D103300AD87A808080EB02779A83763120202020A09BE48FC0AB070808B82ED04D62170302020202DAC56E1240404063C64D02080808080808080808080808080808080808080808180FCCFE855C92A457DDBE864FB560A0D1610000000049454E44AE426082'),
    ROW(90003, '85b774d0de06499338b1926f638a7329ebd0bf064e1af06203be174680063e9f', X'89504E470D0A1A0A0000000D49484452000000A0000000780803000000427C095D00000015504C5445ECF0F3868E96343A4021252940C057FD7E141971C24414EC9E000000BD4944415478DAEDDB410A8330144551DBC2DBFF923BE844C4410B3FF5C79CBB820382240FB26D92A4929E8700010101010155F7B9FED5F7C0D725010202020202020202020202025E0BCCA7A6C0ECEB07CCB15EC09CD508985408C7019312E1306052231C054C8A84AB02932AE1A2C0A44C08D81298D40901010101FDA897043A6E39F2BBD5DDE05EDC7F59E8BFCDF45FB726D8072758588DE88080808080808080808080AB00BB3FD7F8BDC749AD9EC8000202020202DE1C2849D3F6069DA57C447E1E946A0000000049454E44AE426082'),
    ROW(90004, '65e97f1fdddd3969b8ae268852d7e5cab44505123bd287f8fafef29e3e373bea', X'89504E470D0A1A0A0000000D49484452000000A0000000780803000000427C095D00000015504C5445ECF0F3868E96343A4021252940C057FD7E141971C24414EC9E000000BD4944415478DAEDDA3112C2300C45C1C0E7FE6786260534F1406C69C8BE3E33DB3996BC6DD245BBBF02040404040404040404040404040404040404040404FCFCB0B263E0AD344040404040C095C0C70F0102020202020202FE27307B2D8179AF1D30391496029363612530191016029311611D3019129601933121E05AE07925834240404040C0A2096BF7930470A2B0CD947FC61FF50261AB3DC9F9B7BAE9C2769BA6B3270B538D96898080808080808080805F01BB3F5396A48BF50408B23CDF742671A00000000049454E44AE426082'),
    ROW(90005, '85d52443d8bc2d2f9b6e1135327a65274f9facecaf43698243087d2dffb10f58', X'EAB5ACEB82B420EAB590ED9998EAB8B02031ED98B820ECA095EAB8B020ECA090EAB28020EAB8B0EBA19D2028EAB080EC838120EC9E90EBA38C290A2D20ECA090EAB280EC9DBC3A20323032362D31312D31300A2D20ECA090EAB28020EBB680EC849C3A20ECA095EBB3B4ED86B5EC8BA0EB8C80EB8C802031ECA491EB8C8020EC9CA0EC84A0EBB0980A2D20ECA084EC9B90EBB6803A20ECA095EC83812C20EC9888EBB98420ECA084EC9B9020ECA084ED999820EC8B9CED979820ED86B5EAB3BC0A2D20ECA491EAB384EC84A03A2032ED9A8CEC84A020EBAAA8EB919020ECA095EC83812C20EC9EA1EC9D8C20EC9786EC9D8C0A2D20EB82B4EC84A020ECB9B4EB939C3A2033EBB28820ECB9B820ED8FACED8AB820ED9598EB829820EBB688EB9F892C20EC9888EBB98420ED8FACED8AB8EBA19C20EC98AEEAB9800A2D20EB8BA4EC9D8C20ECA090EAB2803A2032303236EB8584203132EC9B9420EB9198ECA7B820ECA3BC0A'),
    ROW(90006, '1b232a7cb9860fad448184fae24241f0219d48c43291f15114d708c51ddb7ef0', X'89504E470D0A1A0A0000000D49484452000000A0000000780803000000427C095D00000015504C5445ECF0F3868E96343A4021252940C057FD7E141971C24414EC9E0000006C4944415478DAEDDAB10D00200845416222FB8FAC858D030816F7BA1F9A1B800849922449FAB071EADA80808080DDC05DE6BDE7ACBD03020202020202020202020202020202020202020202020202020202020202020202020202020216037D60020202FAA396244992A4E72D2DCC1E01CF4AB76E0000000049454E44AE426082')) v
JOIN stored_file f ON f.id = v.column_0 AND f.sha256 = v.column_1
WHERE NOT EXISTS (SELECT 1 FROM stored_file_body b WHERE b.file_id = v.column_0);

INSERT INTO equipment_photo (equipment_id, file_id)
SELECT v.column_0, v.column_1 FROM (VALUES
    ROW(1007, 90001, 'f9a342f7e79a5be22bd07e874d77d1883cb9893e70d6f2a09bfdac1dcefcef81'),
    ROW(1008, 90002, '0a439a23d5bfb7c4f84192b836aecd01b8e682861a29edb8a3c5ef2e41c4e6d2'),
    ROW(1031, 90003, '85b774d0de06499338b1926f638a7329ebd0bf064e1af06203be174680063e9f'),
    ROW(1014, 90004, '65e97f1fdddd3969b8ae268852d7e5cab44505123bd287f8fafef29e3e373bea')) v
JOIN stored_file f ON f.id = v.column_1 AND f.sha256 = v.column_2 AND f.purpose = 'EQUIPMENT_PHOTO'
JOIN stored_file_body b ON b.file_id = f.id
WHERE NOT EXISTS (SELECT 1 FROM equipment_photo p WHERE p.equipment_id = v.column_0 OR p.file_id = v.column_1);

INSERT INTO equipment_file (equipment_id, file_id)
SELECT v.column_0, v.column_1 FROM (VALUES
    ROW(1007, 90005, '85d52443d8bc2d2f9b6e1135327a65274f9facecaf43698243087d2dffb10f58'),
    ROW(1007, 90006, '1b232a7cb9860fad448184fae24241f0219d48c43291f15114d708c51ddb7ef0')) v
JOIN stored_file f ON f.id = v.column_1 AND f.sha256 = v.column_2 AND f.purpose = 'EQUIPMENT_FILE'
JOIN stored_file_body b ON b.file_id = f.id
WHERE NOT EXISTS (SELECT 1 FROM equipment_file p WHERE p.file_id = v.column_1);
