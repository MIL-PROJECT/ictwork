-- 시연용 개인 일정 (명세 7장 시연 데이터, REQ-303 달력 일정 추가, slices/21 9장). 손으로 쓴 파일이다(생성기 없음). 제목·일정은 모두 가상(NFR-002)
-- 이름이 R__seed_work_personal_demo라 설명 이름순으로 다른 시연 시드 다음(마지막)에 돈다(계정만 기댐)
-- 시연 계정 12개(초기데이터 3.1의 사용 중 계정 11개 + 근무지원대대 본부중대장 bn2-co1-cmdr)마다 2026-11-12 ~ 2026-12-07의 훈련·회의·교육·당직·휴가 같은 일정 7~9건, 모두 95건.
-- 내용은 새 직책(정보통신대대장·근무지원대대장, 1중대장·2중대장, 유선반·무선반·전장망PC반 인원, 체계 관리자, 근무지원대대 본부중대장)에 맞춘다.
-- 중요 표시 일부, 여러 날 일정(평일만 표시 포함). 제목 1~100자, 날짜 2000~2099년, 1년 이하, 평일만 표시는 기간에 평일이 있음(CalendarEventService.validate와 같은 규칙)
-- 다른 시연 시드와 맞춤:
--   자리를 비우는 일정(휴가·연가·외박·교육·시험·훈련·연습)은 그 사람의 근무표 배치(R__seed_work_duty_demo)가 없는 날에만 둔다.
--   휴가와 남이 하는 교육은 그 사람이 든 끝나지 않은 업무 계획 칸(R__seed_work_demo)이 없는 날에만 둔다(2026-11-07 통합 확인에서 7건을 옮김).
--   그 사람이 직접 하는 교육(예: 유선반장의 케이블 포설 작업 안전 교육)은 계획 칸과 같은 날이어도 둔다
--   생방송 부서 전장망PC반(slices/21 7장, 11-20 ~ 11-24 비워 둠) 인원은 그 기간에 자리 비우는 일정을 두지 않는다
--   "당직" 일정은 근무표에 그 사람이 배치된 날만 둔다(같은 날·같은 사람)
-- 색은 화면의 네 가지(Danger 훈련·중요 행사, Warning 당직·점검, Primary 회의·업무, Success 교육·휴가)
-- 시연 기준 시각은 2026-11-19(목) 09:00:00(KST, 고정)이다. 만든 시각은 시작일 7일 전 09:00(토·일이면 그 앞 금요일), 늦어도 11-19 08:00
-- id 700001부터. 개인 일정 번호 700001 이상이 쓰였거나(이미 넣음), 쓸 계정(로그인 아이디가 같고 사용 중) 12개 중 하나라도 없으면 전체를 건너뛴다

SET @ok = (SELECT COALESCE(MAX(id), 0) FROM calendar_event) < 700001
    AND (SELECT COUNT(*) FROM account WHERE active = TRUE AND login_id IN
         ('bn1-cmdr', 'bn2-cmdr', 'bn1-co1-cmdr', 'bn1-co2-cmdr', 'bn1-co1-s1-lead', 'bn1-co1-s1-m1', 'bn1-co1-s2-m1',
          'bn1-co1-admin', 'bn1-co2-s1-m1', 'bn1-dual', 'sysadmin', 'bn2-co1-cmdr')) = 12;

-- (번호, 계정, 제목, 시작일, 끝일, 색, 중요, 평일만)
INSERT INTO calendar_event (id, account_id, title, start_date, end_date, color, important, weekdays_only, created_at, updated_at)
SELECT v.id, a.id, v.title, v.start_date, v.end_date, v.color, v.important, v.weekdays_only, c.made, c.made
FROM (VALUES
    -- 정보통신대대장 김민준 (근무표 배치 없음)
    ROW(700001, 'bn1-cmdr', '주간 업무 보고', '2026-11-16', '2026-11-16', 'Primary', FALSE, FALSE),
    ROW(700002, 'bn1-cmdr', '사단 정보통신 운용 점검', '2026-11-17', '2026-11-17', 'Danger', TRUE, FALSE),
    ROW(700003, 'bn1-cmdr', '정보통신 운용 현황 보고', '2026-11-19', '2026-11-19', 'Primary', TRUE, FALSE),
    ROW(700004, 'bn1-cmdr', '동계 전투준비태세 점검 기간', '2026-11-20', '2026-11-27', 'Warning', FALSE, TRUE),
    ROW(700005, 'bn1-cmdr', '주간 업무 보고', '2026-11-23', '2026-11-23', 'Primary', FALSE, FALSE),
    ROW(700006, 'bn1-cmdr', '연대 지휘관 회의', '2026-11-26', '2026-11-26', 'Primary', TRUE, FALSE),
    ROW(700007, 'bn1-cmdr', '주말 부대 순시', '2026-11-28', '2026-11-29', 'Warning', FALSE, FALSE),
    ROW(700008, 'bn1-cmdr', '사단 정보통신 종합 점검', '2026-12-01', '2026-12-03', 'Danger', TRUE, FALSE),
    ROW(700009, 'bn1-cmdr', '주간 업무 보고', '2026-12-07', '2026-12-07', 'Primary', FALSE, FALSE),
    -- 근무지원대대장 안지후 (근무표 배치 없음)
    ROW(700010, 'bn2-cmdr', '보급·수송 계획 회의', '2026-11-12', '2026-11-12', 'Primary', FALSE, FALSE),
    ROW(700011, 'bn2-cmdr', '주간 업무 보고', '2026-11-16', '2026-11-16', 'Primary', FALSE, FALSE),
    ROW(700012, 'bn2-cmdr', '전 간부 정보보호 교육', '2026-11-18', '2026-11-18', 'Success', FALSE, FALSE),
    ROW(700013, 'bn2-cmdr', '대대 지휘관 회의', '2026-11-19', '2026-11-19', 'Primary', TRUE, FALSE),
    ROW(700014, 'bn2-cmdr', '동계 급양·피복 보급 점검', '2026-11-24', '2026-11-25', 'Warning', FALSE, FALSE),
    ROW(700015, 'bn2-cmdr', '의무중대 진료 지원 현장 확인', '2026-11-27', '2026-11-27', 'Primary', FALSE, FALSE),
    ROW(700016, 'bn2-cmdr', '동계 장비 점검 기간', '2026-11-30', '2026-12-04', 'Warning', FALSE, TRUE),
    ROW(700017, 'bn2-cmdr', '연대 작전 회의', '2026-12-02', '2026-12-02', 'Primary', TRUE, FALSE),
    -- 정보통신대대 1중대장 윤도현 (중대 당직 주말: 11-14·11-28·12-12)
    ROW(700018, 'bn1-co1-cmdr', '중대 당직(주말)', '2026-11-14', '2026-11-14', 'Warning', FALSE, FALSE),
    ROW(700019, 'bn1-co1-cmdr', '중대 지휘관 회의', '2026-11-16', '2026-11-16', 'Primary', FALSE, FALSE),
    ROW(700020, 'bn1-co1-cmdr', '분대장 리더십 교육', '2026-11-16', '2026-11-16', 'Success', FALSE, FALSE),
    ROW(700021, 'bn1-co1-cmdr', '중대 통신창고 정리 확인', '2026-11-19', '2026-11-19', 'Primary', TRUE, FALSE),
    ROW(700022, 'bn1-co1-cmdr', '중대 사격 훈련', '2026-11-24', '2026-11-25', 'Danger', TRUE, FALSE),
    ROW(700023, 'bn1-co1-cmdr', '중대 당직(주말)', '2026-11-28', '2026-11-28', 'Warning', FALSE, FALSE),
    ROW(700024, 'bn1-co1-cmdr', '사단 정보통신 종합 점검 지원', '2026-12-01', '2026-12-03', 'Danger', TRUE, FALSE),
    ROW(700025, 'bn1-co1-cmdr', '전입 인원 면담', '2026-12-04', '2026-12-04', 'Success', FALSE, FALSE),
    -- 정보통신대대 2중대장 한정우 (근무표 배치 없음)
    ROW(700026, 'bn1-co2-cmdr', '2중대 안전 교육', '2026-11-13', '2026-11-13', 'Success', FALSE, FALSE),
    ROW(700027, 'bn1-co2-cmdr', '주간 업무 보고', '2026-11-16', '2026-11-16', 'Primary', FALSE, FALSE),
    ROW(700028, 'bn1-co2-cmdr', '지휘관 회의 화상회의 준비 확인', '2026-11-19', '2026-11-19', 'Primary', TRUE, FALSE),
    ROW(700029, 'bn1-co2-cmdr', '동계 장비 보관 상태 점검', '2026-11-23', '2026-11-27', 'Warning', FALSE, TRUE),
    ROW(700030, 'bn1-co2-cmdr', '중대 체육 활동', '2026-11-26', '2026-11-26', 'Success', FALSE, FALSE),
    ROW(700031, 'bn1-co2-cmdr', '사단 정보통신 종합 점검 지원', '2026-12-01', '2026-12-03', 'Danger', TRUE, FALSE),
    ROW(700032, 'bn1-co2-cmdr', '연가', '2026-12-07', '2026-12-07', 'Success', TRUE, FALSE),
    -- 1중대 유선반장 권승현 (중대 당직 주말: 11-15·11-29)
    ROW(700033, 'bn1-co1-s1-lead', '반 회의', '2026-11-12', '2026-11-12', 'Primary', FALSE, FALSE),
    ROW(700034, 'bn1-co1-s1-lead', '중대 당직(주말)', '2026-11-15', '2026-11-15', 'Warning', FALSE, FALSE),
    ROW(700035, 'bn1-co1-s1-lead', '구내 교환기 정기 점검', '2026-11-17', '2026-11-17', 'Warning', FALSE, FALSE),
    ROW(700036, 'bn1-co1-s1-lead', '회의실 전화기 교체 확인', '2026-11-19', '2026-11-19', 'Primary', TRUE, FALSE),
    ROW(700037, 'bn1-co1-s1-lead', '부사관 보수 교육', '2026-11-16', '2026-11-16', 'Success', TRUE, FALSE),
    ROW(700038, 'bn1-co1-s1-lead', '케이블 포설 작업 안전 교육', '2026-11-26', '2026-11-26', 'Success', FALSE, FALSE),
    ROW(700039, 'bn1-co1-s1-lead', '중대 당직(주말)', '2026-11-29', '2026-11-29', 'Warning', FALSE, FALSE),
    ROW(700040, 'bn1-co1-s1-lead', '반 회의', '2026-12-03', '2026-12-03', 'Primary', FALSE, FALSE),
    -- 1중대 유선반 반원 이예준 (중대 당직 평일: 11-12·11-18·11-24·11-30·12-04)
    ROW(700041, 'bn1-co1-s1-m1', '중대 당직 근무', '2026-11-12', '2026-11-12', 'Warning', FALSE, FALSE),
    ROW(700042, 'bn1-co1-s1-m1', '체력 측정', '2026-11-13', '2026-11-13', 'Primary', FALSE, FALSE),
    ROW(700043, 'bn1-co1-s1-m1', '중대 당직 근무', '2026-11-18', '2026-11-18', 'Warning', FALSE, FALSE),
    ROW(700044, 'bn1-co1-s1-m1', '정보처리 자격 시험', '2026-11-21', '2026-11-21', 'Primary', TRUE, FALSE),
    ROW(700045, 'bn1-co1-s1-m1', '중대 당직 근무', '2026-11-24', '2026-11-24', 'Warning', FALSE, FALSE),
    ROW(700046, 'bn1-co1-s1-m1', '단자함 번호 정리', '2026-11-26', '2026-11-26', 'Primary', FALSE, FALSE),
    ROW(700047, 'bn1-co1-s1-m1', '중대 당직 근무', '2026-11-30', '2026-11-30', 'Warning', FALSE, FALSE),
    ROW(700048, 'bn1-co1-s1-m1', '외박', '2026-12-05', '2026-12-06', 'Success', TRUE, FALSE),
    -- 1중대 무선반 반원 임현준 (중대 당직 평일: 11-13·11-19·11-25·12-01·12-07)
    ROW(700049, 'bn1-co1-s2-m1', '중대 당직 근무', '2026-11-13', '2026-11-13', 'Warning', FALSE, FALSE),
    ROW(700050, 'bn1-co1-s2-m1', '무전기 배터리 교체', '2026-11-16', '2026-11-16', 'Warning', FALSE, FALSE),
    ROW(700051, 'bn1-co1-s2-m1', '중대 당직 근무', '2026-11-19', '2026-11-19', 'Warning', FALSE, FALSE),
    ROW(700052, 'bn1-co1-s2-m1', '야전 발전기 시동 점검', '2026-11-20', '2026-11-20', 'Warning', FALSE, FALSE),
    ROW(700053, 'bn1-co1-s2-m1', '중대 당직 근무', '2026-11-25', '2026-11-25', 'Warning', FALSE, FALSE),
    ROW(700054, 'bn1-co1-s2-m1', '정기 휴가', '2026-12-04', '2026-12-06', 'Success', TRUE, FALSE),
    ROW(700055, 'bn1-co1-s2-m1', '무전기 운용 교육', '2026-11-16', '2026-11-16', 'Success', FALSE, FALSE),
    ROW(700056, 'bn1-co1-s2-m1', '중대 당직 근무', '2026-12-07', '2026-12-07', 'Warning', FALSE, FALSE),
    -- 1중대 유선반 반원·1중대 부서 관리자 장선우 (중대 당직 평일: 11-16·11-20·11-26·12-02)
    ROW(700057, 'bn1-co1-admin', '부서 관리 교육', '2026-11-12', '2026-11-12', 'Success', FALSE, FALSE),
    ROW(700058, 'bn1-co1-admin', '중대 당직 근무', '2026-11-16', '2026-11-16', 'Warning', FALSE, FALSE),
    ROW(700059, 'bn1-co1-admin', '12월 중대 당직 근무표 작성', '2026-11-18', '2026-11-18', 'Primary', TRUE, FALSE),
    ROW(700060, 'bn1-co1-admin', '중대 당직 근무', '2026-11-20', '2026-11-20', 'Warning', FALSE, FALSE),
    ROW(700061, 'bn1-co1-admin', '장비 대장 정리 기간', '2026-11-23', '2026-11-27', 'Warning', FALSE, TRUE),
    ROW(700062, 'bn1-co1-admin', '중대 당직 근무', '2026-11-26', '2026-11-26', 'Warning', FALSE, FALSE),
    ROW(700063, 'bn1-co1-admin', '외박', '2026-11-28', '2026-11-29', 'Success', TRUE, FALSE),
    ROW(700064, 'bn1-co1-admin', '중대 당직 근무', '2026-12-02', '2026-12-02', 'Warning', FALSE, FALSE),
    -- 2중대 전장망PC반 반원 정건우 (근무표 배치 없음, 생방송 부서라 11-20 ~ 11-24에 자리 비우는 일정 없음)
    ROW(700065, 'bn1-co2-s1-m1', '체력 측정', '2026-11-12', '2026-11-12', 'Primary', FALSE, FALSE),
    ROW(700066, 'bn1-co2-s1-m1', '전장망 단말 정기 점검 기간', '2026-11-16', '2026-11-18', 'Warning', FALSE, TRUE),
    ROW(700067, 'bn1-co2-s1-m1', '업무용 PC 부팅 불가 건 확인', '2026-11-19', '2026-11-19', 'Primary', TRUE, FALSE),
    ROW(700068, 'bn1-co2-s1-m1', '프린터 드라이버 설치 안내 정리', '2026-11-25', '2026-11-25', 'Primary', FALSE, FALSE),
    ROW(700069, 'bn1-co2-s1-m1', '정기 휴가', '2026-11-14', '2026-11-16', 'Success', TRUE, FALSE),
    ROW(700070, 'bn1-co2-s1-m1', '정보보호 교육', '2026-12-03', '2026-12-03', 'Success', FALSE, FALSE),
    ROW(700071, 'bn1-co2-s1-m1', '종합 점검 반출 장비 반납 확인', '2026-12-04', '2026-12-04', 'Primary', FALSE, FALSE),
    -- 1중대 무선반 반원·무선반 부서 관리자 안지우 (중대 당직 평일: 11-17·11-23·11-27·12-03)
    ROW(700072, 'bn1-dual', '무전기 충전기 대장 정리', '2026-11-13', '2026-11-13', 'Primary', FALSE, FALSE),
    ROW(700073, 'bn1-dual', '중대 당직 근무', '2026-11-17', '2026-11-17', 'Warning', FALSE, FALSE),
    ROW(700074, 'bn1-dual', '휴대용 무전기 감도 점검', '2026-11-19', '2026-11-19', 'Warning', FALSE, FALSE),
    ROW(700075, 'bn1-dual', '중대 당직 근무', '2026-11-23', '2026-11-23', 'Warning', FALSE, FALSE),
    ROW(700076, 'bn1-dual', '신병 면담', '2026-11-25', '2026-11-25', 'Primary', FALSE, FALSE),
    ROW(700077, 'bn1-dual', '중대 당직 근무', '2026-11-27', '2026-11-27', 'Warning', FALSE, FALSE),
    ROW(700078, 'bn1-dual', '위로 휴가', '2026-11-28', '2026-11-28', 'Success', TRUE, FALSE),
    ROW(700079, 'bn1-dual', '중대 당직 근무', '2026-12-03', '2026-12-03', 'Warning', FALSE, FALSE),
    -- 체계 관리자 황은우 (정보통신대대 직속 군무원, 근무표 배치 없음)
    ROW(700080, 'sysadmin', '체계 정기 백업 점검', '2026-11-12', '2026-11-12', 'Warning', FALSE, FALSE),
    ROW(700081, 'sysadmin', '주간 업무 보고', '2026-11-16', '2026-11-16', 'Primary', FALSE, FALSE),
    ROW(700082, 'sysadmin', '전입 인원 계정 등록', '2026-11-17', '2026-11-17', 'Primary', TRUE, FALSE),
    ROW(700083, 'sysadmin', '체계 운용 현황 보고', '2026-11-19', '2026-11-19', 'Primary', TRUE, FALSE),
    ROW(700084, 'sysadmin', '보안 패치 적용 기간', '2026-11-23', '2026-11-27', 'Danger', TRUE, TRUE),
    ROW(700085, 'sysadmin', '외부 정비업체 방문', '2026-11-25', '2026-11-25', 'Primary', FALSE, FALSE),
    ROW(700086, 'sysadmin', '월간 체계 운영 보고', '2026-11-30', '2026-11-30', 'Primary', FALSE, FALSE),
    ROW(700087, 'sysadmin', '체계 사용자 교육', '2026-12-01', '2026-12-03', 'Success', FALSE, FALSE),
    ROW(700088, 'sysadmin', '전산실 전원 점검', '2026-12-04', '2026-12-04', 'Warning', FALSE, FALSE),
    -- 근무지원대대 본부중대장 최유준 (대대 당직 주말: 11-21·11-29·12-12)
    ROW(700089, 'bn2-co1-cmdr', '교육장 사용 일정 정리', '2026-11-13', '2026-11-13', 'Primary', FALSE, FALSE),
    ROW(700090, 'bn2-co1-cmdr', '주간 업무 보고', '2026-11-16', '2026-11-16', 'Primary', FALSE, FALSE),
    ROW(700091, 'bn2-co1-cmdr', '대대 지휘관 회의', '2026-11-19', '2026-11-19', 'Primary', TRUE, FALSE),
    ROW(700092, 'bn2-co1-cmdr', '대대 당직(주말)', '2026-11-21', '2026-11-21', 'Warning', FALSE, FALSE),
    ROW(700093, 'bn2-co1-cmdr', '교육장 화상회의 연결 협조 확인', '2026-11-24', '2026-11-24', 'Primary', FALSE, FALSE),
    ROW(700094, 'bn2-co1-cmdr', '대대 당직(주말)', '2026-11-29', '2026-11-29', 'Warning', FALSE, FALSE),
    ROW(700095, 'bn2-co1-cmdr', '연가', '2026-12-02', '2026-12-04', 'Success', TRUE, FALSE)
) AS v (id, login_id, title, start_date, end_date, color, important, weekdays_only)
JOIN account a ON a.login_id = v.login_id AND a.active = TRUE
-- 만든 시각: 시작일 7일 전(토·일이면 그 앞 금요일) 09:00, 늦어도 기준 시각 전 11-19 08:00
CROSS JOIN LATERAL (
    SELECT LEAST(TIMESTAMP(DATE(v.start_date) - INTERVAL 7 DAY
                           - INTERVAL CASE DAYOFWEEK(DATE(v.start_date) - INTERVAL 7 DAY) WHEN 1 THEN 2 WHEN 7 THEN 1 ELSE 0 END DAY, '09:00:00'),
                 TIMESTAMP('2026-11-19 08:00:00')) AS made
) c
WHERE @ok;

-- 넣은 건수 (조건에 걸려 조용히 건너뛰었는지 서버 기동 로그에서 보이게)
SELECT @ok AS personal_demo_applied, (SELECT COUNT(*) FROM calendar_event WHERE id >= 700001) AS personal_events;
